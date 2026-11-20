package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LaptopFileTransferTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void downloadsAFileAsAStreamAndVerifiesItsLength() throws Exception {
        byte[] original = new byte[2 * 1024 * 1024 + 17];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i * 31);
        try (FakeHttpServer helper = new FakeHttpServer()) {
            helper.route("/file/movie.mkv", request -> new FakeHttpServer.Reply(200, original));
            File inbox = new File(temporary.getRoot(), "received");
            File downloaded = LaptopFileTransfer.download(null, "http://127.0.0.1:" + helper.port() + "/file/movie.mkv",
                    inbox, "movie.mkv", original.length);
            assertEquals(original.length, downloaded.length());
            assertArrayEquals(original, Files.readAllBytes(downloaded.toPath()));
            assertTrue(downloaded.getName().endsWith("-movie.mkv"));
            assertEquals(1, inbox.listFiles().length);
        }
    }

    @Test public void removesPartialOutputWhenTheListedLengthIsWrong() throws Exception {
        byte[] original = "complete file".getBytes(StandardCharsets.UTF_8);
        try (FakeHttpServer helper = new FakeHttpServer()) {
            helper.route("/file/a.txt", request -> new FakeHttpServer.Reply(200, original));
            File inbox = new File(temporary.getRoot(), "received");
            try {
                LaptopFileTransfer.download(null, "http://127.0.0.1:" + helper.port() + "/file/a.txt", inbox, "a.txt", original.length + 1);
                fail("changed or truncated content must be refused");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("file changed"));
            }
            File[] leftovers = inbox.listFiles();
            assertTrue(leftovers == null || leftovers.length == 0);
        }
    }

    @Test public void sanitizesLaptopFileNamesBeforeSaving() {
        assertEquals("movie_1_.mkv", LaptopFileTransfer.safeName("movie/1?.mkv"));
        assertEquals("shared-file", LaptopFileTransfer.safeName(".."));
        assertFalse(LaptopFileTransfer.safeName("file.txt").contains("\\"));
    }
}
