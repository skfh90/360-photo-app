package com.n30dyn4m1c.photosphere.metadata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes GPano XMP into a stitched equirectangular JPEG.
 *
 * <p>Without this the output is just a wide picture: what makes a viewer open a
 * file as a pannable 360° photo is an XMP packet in the {@code GPano} namespace, and
 * neither {@code ExifInterface} nor {@code Bitmap.compress} can put one there.
 *
 * <p><b>The pixels are never touched.</b> The JPEG is not decoded and not re-encoded —
 * this walks the file's marker segments, drops any XMP packet already present,
 * splices in a new {@code APP1} segment, and copies every remaining byte (including
 * the whole entropy-coded scan) through verbatim. Re-encoding to add metadata
 * would cost a generation of JPEG loss on an image that has already been through
 * one on the way in.
 *
 * <h3>Where the segment goes</h3>
 *
 * <p>A JPEG is {@code SOI} followed by marker segments; the metadata ones ({@code APPn}) sit at
 * the front, before the quantisation tables and the scan. The new packet is
 * placed after the last {@code APP0}/{@code APP1} — that is, after JFIF and after EXIF if
 * they are there — which is where readers expect to find it, and before anything
 * heavier such as an ICC profile.
 *
 * <p>Everything here is plain {@code java.io}, so it is unit-testable off-device.
 */
public final class GPanoXmpInjector {

    /** The Photo Sphere namespace itself. */
    public static final String GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/";

    private static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** Identifies an {@code APP1} segment as carrying XMP rather than EXIF. */
    private static final String XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/";

    /**
     * Second half of a packet too large for one segment. Never written here —
     * the GPano packet is a few hundred bytes — but recognised so that replacing
     * someone else's XMP cannot leave an orphaned continuation behind.
     */
    private static final String XMP_EXTENSION_NAMESPACE = "http://ns.adobe.com/xmp/extension/";

    /** Tool marker written into the packet, for anyone inspecting the file later. */
    private static final String TOOLKIT = "PhotoSphere";

    private static final byte[] XMP_SIGNATURE =
            (XMP_NAMESPACE + "\u0000").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] XMP_EXTENSION_SIGNATURE =
            (XMP_EXTENSION_NAMESPACE + "\u0000").getBytes(StandardCharsets.US_ASCII);

    private static final int MARKER_PREFIX = 0xFF;
    private static final int MARKER_SOI = 0xD8;
    private static final int MARKER_APP0 = 0xE0;
    private static final int MARKER_APP1 = 0xE1;
    private static final int MARKER_APP15 = 0xEF;
    private static final int MARKER_COM = 0xFE;

    /** The two bytes of a segment's length field, which the field itself counts. */
    private static final int SEGMENT_LENGTH_BYTES = 2;

    /** Largest value that field can hold, and so the largest segment. */
    private static final int MAX_SEGMENT_LENGTH = 0xFFFF;

    /** Suffix of the file written beside the target while it is being rebuilt. */
    private static final String TEMP_SUFFIX = ".gpano.tmp";

    private GPanoXmpInjector() {
    }

    /**
     * Outcome of an in-place inject: the rewritten file on success, or the
     * failure that left the original untouched.
     */
    public static final class Result {
        private final File file;
        private final Throwable error;

        private Result(File file, Throwable error) {
            this.file = file;
            this.error = error;
        }

        public static Result success(File file) {
            return new Result(file, null);
        }

        public static Result failure(Throwable error) {
            return new Result(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }

        public boolean isFailure() {
            return error != null;
        }

        public File getFile() {
            return file;
        }

        public Throwable getError() {
            return error;
        }

        public File getOrThrow() throws IOException {
            if (error != null) {
                if (error instanceof IOException) {
                    throw (IOException) error;
                }
                throw new IOException(error.getMessage(), error);
            }
            return file;
        }
    }

    /**
     * Injects full-sphere GPano metadata into {@code file}, in place.
     *
     * <p>{@code imageWidth} and {@code imageHeight} must be the JPEG's real pixel dimensions;
     * they are what a viewer uses to map pixels onto the sphere, so a wrong value
     * shows up as a panorama that does not close or that is squashed at the
     * poles.
     *
     * <p>Blocking I/O over the whole file — call off the main thread. Failures come
     * back as a failed {@link Result} rather than an exception: a sphere whose metadata
     * could not be written is still a perfectly good image, and losing it over a
     * header would be the worse outcome.
     */
    public static Result inject(File file, int imageWidth, int imageHeight) {
        return inject(file, GPanoMetadata.forFullPano(imageWidth, imageHeight));
    }

    /** Injects {@code metadata} into {@code file}, in place. See the overload above. */
    public static Result inject(File file, GPanoMetadata metadata) {
        File temp = new File(file.getParentFile(), file.getName() + TEMP_SUFFIX);
        try {
            BufferedInputStream source = new BufferedInputStream(new FileInputStream(file));
            try {
                BufferedOutputStream sink = new BufferedOutputStream(new FileOutputStream(temp));
                try {
                    inject(source, sink, metadata);
                } finally {
                    sink.close();
                }
            } finally {
                source.close();
            }
            if (!temp.renameTo(file)) {
                // Some filesystems refuse to rename onto an existing name. The
                // original is fully readable until this point; replace it
                // atomically rather than delete-then-rename, which would open a
                // window where neither copy exists if the second call failed.
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IOException(
                            "Could not move " + temp.getName() + " onto " + file.getName(), e);
                }
            }
            return Result.success(file);
        } catch (Exception e) {
            return Result.failure(e);
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /**
     * Copies the JPEG on {@code source} to {@code sink} with {@code metadata} spliced into it.
     *
     * <p>Neither stream is closed. Throws {@link IOException} if {@code source} is not a JPEG,
     * ends mid-segment, or the packet will not fit in one {@code APP1} segment.
     */
    public static void inject(InputStream source, OutputStream sink, GPanoMetadata metadata)
            throws IOException {
        byte[] packet = buildXmpPacket(metadata).getBytes(StandardCharsets.UTF_8);
        int segmentLength = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.length + packet.length;
        if (segmentLength > MAX_SEGMENT_LENGTH) {
            throw new IOException(
                    "XMP packet is " + segmentLength + " bytes, too large for one segment");
        }

        if (source.read() != MARKER_PREFIX || source.read() != MARKER_SOI) {
            throw new IOException("Not a JPEG: the file does not start with SOI");
        }
        sink.write(MARKER_PREFIX);
        sink.write(MARKER_SOI);

        LeadingSegments leading = readLeadingSegments(source);

        // After JFIF and EXIF, before an ICC profile or anything else.
        int insertAt = 0;
        for (int i = 0; i < leading.segments.size(); i++) {
            int marker = leading.segments.get(i).marker;
            if (marker == MARKER_APP0 || marker == MARKER_APP1) {
                insertAt = i + 1;
            }
        }

        for (int i = 0; i < insertAt; i++) {
            leading.segments.get(i).writeTo(sink);
        }
        writeXmpSegment(sink, packet);
        for (int i = insertAt; i < leading.segments.size(); i++) {
            leading.segments.get(i).writeTo(sink);
        }

        // From the first non-metadata marker on, the file is copied byte for
        // byte: tables, frame header, and the entropy-coded scan untouched.
        sink.write(MARKER_PREFIX);
        sink.write(leading.nextMarker);
        copy(source, sink);
    }

    /**
     * The XMP packet, as the text that goes into the segment.
     *
     * <p>{@code UsePanoramaViewer} is the switch a viewer actually reads; the rest tells
     * it how to wrap the pixels around the sphere.
     */
    public static String buildXmpPacket(GPanoMetadata metadata) {
        StringBuilder out = new StringBuilder();
        out.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n");
        out.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"").append(TOOLKIT).append("\">\n");
        out.append("  <rdf:RDF xmlns:rdf=\"").append(RDF_NAMESPACE).append("\">\n");
        out.append("    <rdf:Description rdf:about=\"\"\n");
        out.append("        xmlns:GPano=\"").append(GPANO_NAMESPACE).append("\"\n");
        out.append("        GPano:UsePanoramaViewer=\"")
                .append(asXmpBoolean(metadata.usePanoramaViewer)).append("\"\n");
        out.append("        GPano:ProjectionType=\"")
                .append(escapeXmlAttribute(metadata.projectionType)).append("\"\n");
        out.append("        GPano:FullPanoWidthPixels=\"")
                .append(metadata.fullPanoWidthPixels).append("\"\n");
        out.append("        GPano:FullPanoHeightPixels=\"")
                .append(metadata.fullPanoHeightPixels).append("\"\n");
        out.append("        GPano:CroppedAreaImageWidthPixels=")
                .append("\"").append(metadata.croppedAreaImageWidthPixels).append("\"\n");
        out.append("        GPano:CroppedAreaImageHeightPixels=")
                .append("\"").append(metadata.croppedAreaImageHeightPixels).append("\"\n");
        out.append("        GPano:CroppedAreaLeftPixels=\"")
                .append(metadata.croppedAreaLeftPixels).append("\"\n");
        out.append("        GPano:CroppedAreaTopPixels=\"")
                .append(metadata.croppedAreaTopPixels).append("\"/>\n");
        out.append("  </rdf:RDF>\n");
        out.append("</x:xmpmeta>\n");
        // "r" for read-only: no padding follows, so nothing may edit the packet
        // in place. Rewriting the segment, as this class does, is still fine.
        out.append("<?xpacket end=\"r\"?>");
        return out.toString();
    }

    /** One marker segment, held while the metadata block is reordered. */
    private static final class Segment {
        final int marker;
        final byte[] payload;

        Segment(int marker, byte[] payload) {
            this.marker = marker;
            this.payload = payload;
        }

        void writeTo(OutputStream sink) throws IOException {
            int length = SEGMENT_LENGTH_BYTES + payload.length;
            sink.write(MARKER_PREFIX);
            sink.write(marker);
            sink.write(length >>> 8);
            sink.write(length & 0xFF);
            sink.write(payload);
        }
    }

    private static final class LeadingSegments {
        final List<Segment> segments;
        final int nextMarker;

        LeadingSegments(List<Segment> segments, int nextMarker) {
            this.segments = segments;
            this.nextMarker = nextMarker;
        }
    }

    /**
     * Reads the run of metadata segments at the head of the file, dropping any
     * XMP already there, and stops on the first marker that is not one.
     *
     * <p>Returns those segments plus the marker it stopped on, which the caller
     * still has to write — it has been consumed from {@code source} but belongs to the
     * verbatim tail.
     */
    private static LeadingSegments readLeadingSegments(InputStream source) throws IOException {
        List<Segment> segments = new ArrayList<Segment>();
        while (true) {
            int prefix = source.read();
            if (prefix == -1) {
                throw new IOException("JPEG ended before any image data");
            }
            if (prefix != MARKER_PREFIX) {
                throw new IOException("Expected a marker, found 0x" + Integer.toHexString(prefix));
            }

            // Any number of 0xFF bytes may pad the gap before a marker.
            int marker = source.read();
            while (marker == MARKER_PREFIX) {
                marker = source.read();
            }
            if (marker == -1) {
                throw new IOException("JPEG ended on a bare marker prefix");
            }

            boolean isMetadata =
                    (marker >= MARKER_APP0 && marker <= MARKER_APP15) || marker == MARKER_COM;
            if (!isMetadata) {
                return new LeadingSegments(segments, marker);
            }

            int length = (readOrThrow(source) << 8) | readOrThrow(source);
            if (length < SEGMENT_LENGTH_BYTES) {
                throw new IOException(
                        "Segment 0x" + Integer.toHexString(marker) + " declares " + length + " bytes");
            }
            byte[] payload = readExactly(source, length - SEGMENT_LENGTH_BYTES);

            // Replacing rather than appending: two XMP packets in one file is
            // undefined, and readers generally take the first one they find.
            boolean isExistingXmp = marker == MARKER_APP1
                    && (startsWith(payload, XMP_SIGNATURE)
                    || startsWith(payload, XMP_EXTENSION_SIGNATURE));
            if (!isExistingXmp) {
                segments.add(new Segment(marker, payload));
            }
        }
    }

    private static void writeXmpSegment(OutputStream sink, byte[] packet) throws IOException {
        int length = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.length + packet.length;
        sink.write(MARKER_PREFIX);
        sink.write(MARKER_APP1);
        sink.write(length >>> 8);
        sink.write(length & 0xFF);
        sink.write(XMP_SIGNATURE);
        sink.write(packet);
    }

    /** XMP booleans are the words, capitalised — not {@code 1}/{@code 0} or lowercase. */
    private static String asXmpBoolean(boolean value) {
        return value ? "True" : "False";
    }

    private static String escapeXmlAttribute(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    out.append(character);
                    break;
            }
        }
        return out.toString();
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readOrThrow(InputStream source) throws IOException {
        int value = source.read();
        if (value == -1) {
            throw new EOFException("JPEG ended inside a segment header");
        }
        return value;
    }

    private static byte[] readExactly(InputStream source, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = source.read(bytes, offset, count - offset);
            if (read < 0) {
                throw new EOFException(
                        "JPEG ended " + offset + "/" + count + " bytes into a segment");
            }
            offset += read;
        }
        return bytes;
    }

    private static void copy(InputStream source, OutputStream sink) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) >= 0) {
            sink.write(buffer, 0, read);
        }
    }
}
