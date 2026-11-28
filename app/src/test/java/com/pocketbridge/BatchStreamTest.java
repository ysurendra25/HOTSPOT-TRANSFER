package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BatchStreamTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File file(String name, byte[] data) throws IOException {
        File f = new File(temporary.getRoot(), "src-" + name);
        Files.write(f.toPath(), data);
        return f;
    }

    private static byte[] bytes(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * seed + 3);
        return b;
    }

    @Test public void roundTripKeepsFoldersBytesAndExactLength() throws Exception {
        List<String> paths = Arrays.asList("Trip/day 1/a.jpg", "Trip/day 2/b.jpg", "Trip/empty.txt", "notes ü.txt", "Trip/day 1/a.jpg");
        List<File> files = new ArrayList<>();
        byte[][] data = {bytes(300_000, 7), bytes(1, 3), new byte[0], "héllo".getBytes(StandardCharsets.UTF_8), bytes(10, 5)};
        for (int i = 0; i < data.length; i++) files.add(file("f" + i, data[i]));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long written = BatchStream.write(body, paths, files);
        assertEquals(body.size(), written);
        assertEquals(BatchStream.length(paths, files), written);

        File root = temporary.newFolder("received");
        // Trailing bytes after the batch belong to the next request and must not be read.
        byte[] withTrailer = Arrays.copyOf(body.toByteArray(), body.size() + 5);
        List<BatchStream.Entry> got = BatchStream.read(new ByteArrayInputStream(withTrailer), written, root,
                new BufferPool(64 * 1024, 1, false));
        assertEquals(5, got.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(paths.get(i), got.get(i).path);
            assertArrayEquals(data[i], Files.readAllBytes(got.get(i).file.toPath()));
        }
        assertTrue(new File(root, "Trip/day 1/a.jpg").isFile());
        assertEquals("a name clash inside a batch is kept, numbered", "a (2).jpg", got.get(4).file.getName());
        assertArrayEquals(data[4], Files.readAllBytes(got.get(4).file.toPath()));
    }

    @Test public void worksWithADirectBufferPool() throws Exception {
        List<File> files = Arrays.asList(file("x", bytes(70_000, 11)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        BatchStream.write(body, Arrays.asList("x.bin"), files);
        List<BatchStream.Entry> got = BatchStream.read(new ByteArrayInputStream(body.toByteArray()), body.size(),
                temporary.newFolder("d"), new BufferPool(1024, 1, true));
        assertArrayEquals(bytes(70_000, 11), Files.readAllBytes(got.get(0).file.toPath()));
    }

    @Test public void aTruncatedBatchLeavesNothingBehind() throws Exception {
        List<File> files = Arrays.asList(file("a", bytes(1000, 1)), file("b", bytes(1000, 2)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        BatchStream.write(body, Arrays.asList("dir/a", "dir/b"), files);
        byte[] cut = Arrays.copyOf(body.toByteArray(), body.size() - 600);
        File root = temporary.newFolder("cut");
        try {
            BatchStream.read(new ByteArrayInputStream(cut), cut.length, root, new BufferPool(4096, 1, false));
            fail("a truncated batch must fail");
        } catch (IOException expected) { /* ok */ }
        assertFalse(new File(root, "dir/a").exists());
        assertFalse(new File(root, "dir/b").exists());
    }

    @Test public void badMagicAndTraversalAreRefused() throws Exception {
        File root = temporary.newFolder("bad");
        try {
            BatchStream.read(new ByteArrayInputStream("NOPE".getBytes(StandardCharsets.US_ASCII)), 4, root, new BufferPool(16, 1, false));
            fail();
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("Not a PocketBridge batch")); }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        BatchStream.write(body, Arrays.asList("../../escape.txt"), Arrays.asList(file("e", bytes(3, 1))));
        List<BatchStream.Entry> got = BatchStream.read(new ByteArrayInputStream(body.toByteArray()), body.size(), root,
                new BufferPool(16, 1, false));
        assertEquals("escape.txt", got.get(0).path);
        assertTrue(got.get(0).file.getCanonicalPath().startsWith(root.getCanonicalPath()));
        try { BatchStream.header("", 1); fail(); } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
