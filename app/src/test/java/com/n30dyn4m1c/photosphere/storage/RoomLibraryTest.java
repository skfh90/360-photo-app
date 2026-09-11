package com.n30dyn4m1c.photosphere.storage;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class RoomLibraryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void copyIntoKeepsTheSourceAndAddsARoomJpeg() throws Exception {
        File source = folder.newFile("stitch.jpg");
        writeBytes(source, new byte[] {1, 2, 3, 4});
        File rooms = folder.newFolder("parent");

        File dest = RoomLibrary.copyInto(new File(rooms, RoomLibrary.DIRECTORY), source);

        Assert.assertTrue(dest.isFile());
        Assert.assertTrue(dest.getName().startsWith("room_"));
        Assert.assertTrue(dest.getName().endsWith(".jpg"));
        Assert.assertEquals(4L, dest.length());
        Assert.assertTrue(source.exists());
    }

    @Test
    public void listFilesReturnsNewestJpegFirstAndIgnoresOtherTypes() throws Exception {
        File rooms = folder.newFolder(RoomLibrary.DIRECTORY);
        File older = new File(rooms, "room_a.jpg");
        File newer = new File(rooms, "room_b.jpg");
        writeBytes(older, new byte[] {1});
        writeBytes(newer, new byte[] {2});
        writeBytes(new File(rooms, "notes.txt"), new byte[] {3});
        Assert.assertTrue(older.setLastModified(1_000L));
        Assert.assertTrue(newer.setLastModified(2_000L));

        List<File> listed = RoomLibrary.listFiles(rooms);

        Assert.assertEquals(2, listed.size());
        Assert.assertEquals(newer.getName(), listed.get(0).getName());
        Assert.assertEquals(older.getName(), listed.get(1).getName());
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
    }
}
