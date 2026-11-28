package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RangedDownloaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    static byte[] sha256(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }

    private File part(String name) { return new File(temporary.getRoot(), name + ".part"); }

    @Test public void parallelRangesRebuildTheExactFile() throws Exception {
        byte[] data = random(40 * 1024 * 1024 + 123, 1);
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            RangedDownloader.Result result = new RangedDownloader(server.url("/a.bin"), part("a"), data.length, null).download();
            assertTrue(result.ranged);
            assertFalse(result.resumed);
            assertArrayEquals(sha256(data), sha256(Files.readAllBytes(result.file.toPath())));
            assertTrue("a big file uses several connections at once", server.maxActive.get() >= 2);
            assertFalse("the manifest is removed when the file is complete",
                    new File(temporary.getRoot(), "a.part.pbresume").exists());
        }
    }

    @Test public void resumesFromTheManifestAfterAnInterruptionMidRange() throws Exception {
        byte[] data = random(24 * 1024 * 1024, 2);
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            server.budget.set(9 * 1024 * 1024 + 4321); // the link dies part way through a range
            RangedDownloader first = new RangedDownloader(server.url("/m.mkv"), part("m"), data.length, null);
            try { first.download(); fail("the interrupted download must fail"); }
            catch (IOException expected) { /* resumable */ }
            ResumeManifest saved = ResumeManifest.read(first.manifestFile());
            assertTrue("progress is saved for the next attempt", saved != null && saved.completedBytes() > 0);
            long kept = saved.completedBytes();
            assertEquals(data.length, part("m").length());

            server.budget.set(Long.MAX_VALUE);
            long before = server.bodyBytes.get();
            RangedDownloader.Result result = new RangedDownloader(server.url("/m.mkv"), part("m"), data.length, null).download();
            assertTrue(result.resumed);
            long fetchedAgain = server.bodyBytes.get() - before;
            assertTrue("only the missing ranges are fetched again (" + fetchedAgain + ")", fetchedAgain <= data.length - kept + 2);
            assertArrayEquals(sha256(data), sha256(Files.readAllBytes(result.file.toPath())));
        }
    }

    @Test public void aSourceChangedBetweenAttemptsStartsOver() throws Exception {
        byte[] data = random(16 * 1024 * 1024, 3);
        byte[] edited = random(16 * 1024 * 1024, 4);
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            server.budget.set(5 * 1024 * 1024);
            try { new RangedDownloader(server.url("/f"), part("f"), -1, null).download(); fail(); }
            catch (IOException expected) { /* interrupted */ }
            server.budget.set(Long.MAX_VALUE);
            server.setContent(edited); // same size, new ETag
            RangedDownloader.Result result = new RangedDownloader(server.url("/f"), part("f"), -1, null).download();
            assertFalse("an old manifest for another ETag is never trusted", result.resumed);
            assertArrayEquals(sha256(edited), sha256(Files.readAllBytes(result.file.toPath())));
        }
    }

    @Test public void aSourceChangedDuringTheDownloadRestartsWithTheNewFile() throws Exception {
        byte[] data = random(20 * 1024 * 1024, 5);
        byte[] edited = random(20 * 1024 * 1024, 6);
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            server.swapTo = edited;
            server.swapAfter = 2; // after two ranges, If-Range no longer matches: the server answers 200
            RangedDownloader.Result result = new RangedDownloader(server.url("/g"), part("g"), data.length, null).download();
            assertTrue("it restarted", result.restarts >= 1);
            assertArrayEquals(sha256(edited), sha256(Files.readAllBytes(result.file.toPath())));
        }
    }

    @Test public void withoutRangeSupportItFallsBackToOneStream() throws Exception {
        byte[] data = random(3 * 1024 * 1024 + 5, 7);
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            server.ranges(false);
            RangedDownloader.Result result = new RangedDownloader(server.url("/s"), part("s"), data.length, null).download();
            assertFalse(result.ranged);
            assertArrayEquals(data, Files.readAllBytes(result.file.toPath()));
            assertEquals("the probe's own response carried the file", 1, server.requests.get());
        }
    }

    @Test public void aDifferentSizeThanListedIsRefusedAndEmptyFilesWork() throws Exception {
        try (RangeHttpServer server = new RangeHttpServer(random(1000, 8))) {
            try { new RangedDownloader(server.url("/x"), part("x"), 999, null).download(); fail(); }
            catch (RangedDownloader.SizeChanged expected) { /* reload the list */ }
            server.setContent(new byte[0]);
            RangedDownloader.Result empty = new RangedDownloader(server.url("/x"), part("e"), 0, null).download();
            assertEquals(0, empty.size);
            assertEquals(0, empty.file.length());
        }
    }

    @Test public void progressReachesTheTotal() throws Exception {
        byte[] data = random(10 * 1024 * 1024, 9);
        long[] last = {0, 0};
        try (RangeHttpServer server = new RangeHttpServer(data)) {
            new RangedDownloader(server.url("/p"), part("p"), data.length, (done, total) -> {
                synchronized (last) { last[0] = Math.max(last[0], done); last[1] = total; }
            }).download();
        }
        assertEquals(data.length, last[0]);
        assertEquals(data.length, last[1]);
    }
}
