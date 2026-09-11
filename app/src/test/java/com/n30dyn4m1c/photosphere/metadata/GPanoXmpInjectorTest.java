package com.n30dyn4m1c.photosphere.metadata;

import org.junit.Assert;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The injector rewrites a JPEG's header by hand, so what matters is that the
 * bytes it does not own come out identical — a re-encode here would quietly cost
 * the user a generation of image quality on every sphere.
 */
public class GPanoXmpInjectorTest {

    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP15 = 0xEF;
    private static final int COM = 0xFE;
    private static final int DQT = 0xDB;
    private static final int SOS = 0xDA;

    private static final Charset US_ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Test
    public void everyGPanoPropertyGooglePhotosLooksForIsWritten() {
        String packet = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(4096, 2048));

        Assert.assertTrue(packet.contains("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""));
        Assert.assertTrue(packet.contains("GPano:UsePanoramaViewer=\"True\""));
        Assert.assertTrue(packet.contains("GPano:ProjectionType=\"equirectangular\""));
        Assert.assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"4096\""));
        Assert.assertTrue(packet.contains("GPano:FullPanoHeightPixels=\"2048\""));
        Assert.assertTrue(packet.contains("GPano:CroppedAreaImageWidthPixels=\"4096\""));
        Assert.assertTrue(packet.contains("GPano:CroppedAreaImageHeightPixels=\"2048\""));
        Assert.assertTrue(packet.contains("GPano:CroppedAreaLeftPixels=\"0\""));
        Assert.assertTrue(packet.contains("GPano:CroppedAreaTopPixels=\"0\""));
    }

    @Test
    public void thePacketIsAWellFormedXpacketAroundOneRdfDescription() {
        String packet = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(1000, 500));

        Assert.assertTrue(packet.startsWith("<?xpacket begin="));
        Assert.assertTrue(packet.endsWith("<?xpacket end=\"r\"?>"));
        Assert.assertEquals(1, packet.split("<rdf:Description", -1).length - 1);
        Assert.assertTrue(packet.contains("</x:xmpmeta>"));
    }

    @Test
    public void theImageDataIsCopiedThroughByteForByte() throws IOException {
        byte[] tail = tailBytes();
        byte[] source = concat(jpeg(app0Jfif(), exifApp1()), tail);

        byte[] output = inject(source, GPanoMetadata.forFullPano(4096, 2048));

        // Everything from the first non-metadata marker on is the encoded image:
        // tables, frame header and the entropy-coded scan. None of it may move.
        Assert.assertArrayEquals(tail, tailOf(output));
    }

    @Test
    public void theXmpSegmentGoesInAfterJfifAndExif() throws IOException {
        byte[] source = concat(jpeg(app0Jfif(), exifApp1()), tailBytes());

        List<Segment> segments = segmentsOf(inject(source, GPanoMetadata.forFullPano(4096, 2048)));
        List<String> signatures = new ArrayList<String>();
        int[] markers = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            markers[i] = segments.get(i).marker;
            signatures.add(signatureOf(segments.get(i).payload));
        }

        Assert.assertEquals(3, segments.size());
        Assert.assertEquals(APP0, markers[0]);
        Assert.assertEquals("JFIF", signatures.get(0));
        Assert.assertEquals(APP1, markers[1]);
        Assert.assertEquals("Exif", signatures.get(1));
        Assert.assertEquals(APP1, markers[2]);
        Assert.assertEquals("XMP", signatures.get(2));
    }

    @Test
    public void aJpegWithNoMetadataSegmentsAtAllGetsOne() throws IOException {
        byte[] source = concat(jpeg(), tailBytes());

        List<Segment> segments = segmentsOf(inject(source, GPanoMetadata.forFullPano(2048, 1024)));

        Assert.assertEquals(1, segments.size());
        Assert.assertEquals(APP1, segments.get(0).marker);
        Assert.assertTrue(xmpPacketOf(segments.get(0).payload).contains("GPano:FullPanoWidthPixels"));
    }

    @Test
    public void anExistingXmpPacketIsReplacedRatherThanAddedTo() throws IOException {
        String stale = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(640, 320));
        byte[] source = concat(jpeg(app0Jfif(), xmpApp1(stale), iccApp2()), tailBytes());

        List<Segment> segments = segmentsOf(inject(source, GPanoMetadata.forFullPano(4096, 2048)));

        List<Segment> xmpSegments = new ArrayList<Segment>();
        List<String> signatures = new ArrayList<String>();
        for (int i = 0; i < segments.size(); i++) {
            String signature = signatureOf(segments.get(i).payload);
            signatures.add(signature);
            if ("XMP".equals(signature)) {
                xmpSegments.add(segments.get(i));
            }
        }
        Assert.assertEquals(1, xmpSegments.size());
        // The new dimensions, not the stale ones.
        String packet = xmpPacketOf(xmpSegments.get(0).payload);
        Assert.assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"4096\""));
        Assert.assertTrue(!packet.contains("640"));
        // The ICC profile beside it is untouched, and still after the XMP.
        Assert.assertEquals(Arrays.asList("JFIF", "XMP", "ICC_PROFILE"), signatures);
    }

    @Test
    public void injectingTwiceIsTheSameAsInjectingOnce() throws IOException {
        byte[] source = concat(jpeg(app0Jfif(), exifApp1()), tailBytes());
        GPanoMetadata metadata = GPanoMetadata.forFullPano(4096, 2048);

        byte[] once = inject(source, metadata);
        byte[] twice = inject(once, metadata);

        Assert.assertArrayEquals(once, twice);
    }

    @Test
    public void aFileThatDoesNotStartWithSoiIsRefused() {
        final byte[] notAJpeg = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};

        Assert.assertThrows(IOException.class, new ThrowingRunnable() {
            @Override
            public void run() throws Throwable {
                inject(notAJpeg, GPanoMetadata.forFullPano(100, 50));
            }
        });
    }

    @Test
    public void aJpegThatEndsInsideASegmentIsRefused() {
        // A segment declaring 200 bytes of payload with none of them present.
        final byte[] truncated = new byte[] {
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) APP1, 0x00, (byte) 0xC8
        };

        Assert.assertThrows(IOException.class, new ThrowingRunnable() {
            @Override
            public void run() throws Throwable {
                inject(truncated, GPanoMetadata.forFullPano(100, 50));
            }
        });
    }

    @Test
    public void aFullPanoCoversTheWholeImage() {
        GPanoMetadata metadata = GPanoMetadata.forFullPano(4096, 2048);

        Assert.assertEquals(4096, metadata.croppedAreaImageWidthPixels);
        Assert.assertEquals(2048, metadata.croppedAreaImageHeightPixels);
        Assert.assertEquals(0, metadata.croppedAreaLeftPixels);
        Assert.assertEquals(0, metadata.croppedAreaTopPixels);
        Assert.assertEquals(GPanoMetadata.PROJECTION_EQUIRECTANGULAR, metadata.projectionType);
        Assert.assertTrue(metadata.usePanoramaViewer);
    }

    @Test
    public void aFullSphereThroughTheRegionFactoryIsTheFullPano() {
        Assert.assertEquals(
                GPanoMetadata.forFullPano(4096, 2048),
                GPanoMetadata.forSphereRegion(
                        4096,
                        2048,
                        360f,
                        0f,
                        180f,
                        0f));
    }

    @Test
    public void aRingCaptureIsAHorizontalSliceOfTheFullSphere() {
        // A 4096 × 819 image holding a 360° × 72° band about the horizon. The
        // full sphere it slices out of is 4096 wide and 2.5× as tall, and the
        // band sits centred on the horizon.
        GPanoMetadata metadata = GPanoMetadata.forSphereRegion(
                4096,
                819,
                360f,
                0f,
                72f,
                0f);

        Assert.assertEquals(4096, metadata.fullPanoWidthPixels);
        Assert.assertEquals(2048, metadata.fullPanoHeightPixels);
        Assert.assertEquals(4096, metadata.croppedAreaImageWidthPixels);
        Assert.assertEquals(819, metadata.croppedAreaImageHeightPixels);
        Assert.assertEquals(0, metadata.croppedAreaLeftPixels);
        // The band spans ±36°; the top of the canvas is 36° above the horizon,
        // which is (90 − 36)/180 of the way down the full sphere.
        Assert.assertEquals((int) (54.0 / 180.0 * 2048), metadata.croppedAreaTopPixels);
    }

    @Test
    public void aCroppedAreaOutsideTheSphereIsRejected() {
        Assert.assertThrows(IllegalArgumentException.class, new ThrowingRunnable() {
            @Override
            public void run() {
                new GPanoMetadata(
                        4096,
                        2048,
                        4096,
                        2048,
                        1,
                        0);
            }
        });
    }

    private byte[] inject(byte[] source, GPanoMetadata metadata) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        GPanoXmpInjector.inject(new ByteArrayInputStream(source), sink, metadata);
        return sink.toByteArray();
    }

    /** {@code SOI} followed by segments, with no image data after them. */
    private byte[] jpeg(byte[]... segments) {
        byte[] soi = new byte[] {(byte) 0xFF, (byte) 0xD8};
        if (segments == null || segments.length == 0) {
            return soi;
        }
        return concat(soi, concat(segments));
    }

    private byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        byte[] header = new byte[] {
                (byte) 0xFF,
                (byte) marker,
                (byte) (length >>> 8),
                (byte) (length & 0xFF)
        };
        return concat(header, payload);
    }

    private byte[] app0Jfif() {
        return segment(APP0, concat("JFIF\u0000".getBytes(US_ASCII), new byte[] {1, 1, 0, 0, 1, 0, 1, 0, 0}));
    }

    private byte[] exifApp1() {
        byte[] body = new byte[16];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        return segment(APP1, concat("Exif\u0000\u0000".getBytes(US_ASCII), body));
    }

    private byte[] xmpApp1(String packet) {
        return segment(APP1, concat("http://ns.adobe.com/xap/1.0/\u0000".getBytes(US_ASCII), packet.getBytes(UTF_8)));
    }

    private byte[] iccApp2() {
        byte[] body = new byte[8];
        Arrays.fill(body, (byte) 0x7F);
        return segment(APP2, concat("ICC_PROFILE\u0000".getBytes(US_ASCII), body));
    }

    /**
     * A quantisation table, a scan header, some entropy-coded data and {@code EOI}:
     * stand-in for everything the injector must not touch.
     */
    private byte[] tailBytes() {
        byte[] dqt = new byte[65];
        for (int i = 0; i < dqt.length; i++) {
            dqt[i] = (byte) (i * 3);
        }
        byte[] entropy = new byte[512];
        for (int i = 0; i < entropy.length; i++) {
            entropy[i] = (byte) (i % 251);
        }
        return concat(
                segment(DQT, dqt),
                segment(SOS, new byte[] {1, 1, 0, 0, 63, 0}),
                entropy,
                new byte[] {(byte) 0xFF, (byte) 0xD9});
    }

    private List<Segment> segmentsOf(byte[] jpeg) {
        return split(jpeg).segments;
    }

    private byte[] tailOf(byte[] jpeg) {
        return split(jpeg).tail;
    }

    /** Splits a JPEG into its leading metadata segments and everything after. */
    private Split split(byte[] jpeg) {
        if (jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) 0xD8) {
            throw new IllegalArgumentException("no SOI");
        }
        List<Segment> segments = new ArrayList<Segment>();
        int offset = 2;
        while (true) {
            if (jpeg[offset] != (byte) 0xFF) {
                throw new IllegalArgumentException("no marker at " + offset);
            }
            int marker = jpeg[offset + 1] & 0xFF;
            if ((marker < APP0 || marker > APP15) && marker != COM) {
                return new Split(segments, Arrays.copyOfRange(jpeg, offset, jpeg.length));
            }
            int length = ((jpeg[offset + 2] & 0xFF) << 8) | (jpeg[offset + 3] & 0xFF);
            segments.add(new Segment(marker, Arrays.copyOfRange(jpeg, offset + 4, offset + 2 + length)));
            offset += 2 + length;
        }
    }

    /**
     * What an APPn payload says it is: the NUL-terminated identifier it opens
     * with, shortened to "XMP" for the one that is a whole namespace URI.
     */
    private String signatureOf(byte[] payload) {
        String ascii = new String(payload, US_ASCII);
        int nul = ascii.indexOf('\u0000');
        String identifier = nul >= 0 ? ascii.substring(0, nul) : ascii;
        if ("http://ns.adobe.com/xap/1.0/".equals(identifier)) {
            return "XMP";
        }
        return identifier;
    }

    private String xmpPacketOf(byte[] payload) {
        String text = new String(payload, UTF_8);
        int nul = text.indexOf('\u0000');
        return nul >= 0 ? text.substring(nul + 1) : text;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (int i = 0; i < arrays.length; i++) {
            length += arrays[i].length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (int i = 0; i < arrays.length; i++) {
            System.arraycopy(arrays[i], 0, out, offset, arrays[i].length);
            offset += arrays[i].length;
        }
        return out;
    }

    private static final class Segment {
        final int marker;
        final byte[] payload;

        Segment(int marker, byte[] payload) {
            this.marker = marker;
            this.payload = payload;
        }
    }

    private static final class Split {
        final List<Segment> segments;
        final byte[] tail;

        Split(List<Segment> segments, byte[] tail) {
            this.segments = segments;
            this.tail = tail;
        }
    }
}
