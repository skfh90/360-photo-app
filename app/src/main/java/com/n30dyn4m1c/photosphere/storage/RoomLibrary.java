package com.n30dyn4m1c.photosphere.storage;

import android.content.Context;
import android.graphics.BitmapFactory;

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Durable library of finished room previews.
 *
 * Stitched JPEGs start in the cache; this copies them into {@code files/rooms/}
 * so a new capture cannot wipe the interiors the user already made.
 */
public final class RoomLibrary {

    public static final String DIRECTORY = "rooms";

    private RoomLibrary() {
    }

    public static File directory(Context context) {
        return directory(context.getFilesDir());
    }

    public static File directory(File parent) {
        File directory = new File(parent, DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    /**
     * Copies {@code sphere} into the rooms library and returns a handle that
     * points at the kept file. If the sphere is already in that folder it is
     * returned unchanged.
     */
    public static SphereImageStore.StitchedSphere archive(
            Context context,
            SphereImageStore.StitchedSphere sphere
    ) throws IOException {
        if (sphere == null || sphere.file == null) {
            throw new IOException("no room to archive");
        }
        File rooms = directory(context);
        File source = sphere.file.getAbsoluteFile();
        if (source.getParentFile() != null
                && source.getParentFile().getAbsoluteFile().equals(rooms.getAbsoluteFile())) {
            return sphere;
        }
        File dest = copyInto(rooms, source);
        return new SphereImageStore.StitchedSphere(
                dest,
                sphere.width,
                sphere.height,
                sphere.diagnostics,
                sphere.gpano
        );
    }

    public static File copyInto(File roomsDir, File source) throws IOException {
        if (!roomsDir.exists() && !roomsDir.mkdirs()) {
            throw new IOException("could not create " + roomsDir.getAbsolutePath());
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File dest = new File(roomsDir, "room_" + timestamp + ".jpg");
        copyFile(source, dest);
        return dest;
    }

    public static List<File> listFiles(File roomsDir) {
        File[] files = roomsDir == null ? null : roomsDir.listFiles();
        if (files == null || files.length == 0) {
            return new ArrayList<File>();
        }
        List<File> rooms = new ArrayList<File>();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            String name = file.getName().toLowerCase(Locale.US);
            if (file.isFile() && name.endsWith(".jpg")) {
                rooms.add(file);
            }
        }
        File[] sorted = rooms.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long delta = b.lastModified() - a.lastModified();
                if (delta < 0) return -1;
                if (delta > 0) return 1;
                return b.getName().compareTo(a.getName());
            }
        });
        return new ArrayList<File>(Arrays.asList(sorted));
    }

    public static List<File> listFiles(Context context) {
        return listFiles(directory(context));
    }

    public static SphereImageStore.StitchedSphere open(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int width = Math.max(1, bounds.outWidth);
        int height = Math.max(1, bounds.outHeight);
        return new SphereImageStore.StitchedSphere(
                file,
                width,
                height,
                null,
                GPanoMetadata.forFullPano(width, height)
        );
    }

    private static void copyFile(File source, File dest) throws IOException {
        FileInputStream in = new FileInputStream(source);
        try {
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
