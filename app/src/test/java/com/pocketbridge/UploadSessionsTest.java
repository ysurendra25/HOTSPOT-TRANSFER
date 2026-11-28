package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UploadSessionsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final BufferPool pool = new BufferPool(64 * 1024, 4, false);

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static InputStream part(byte[] data, long from, long to) {
        return new ByteArrayInputStream(Arrays.copyOfRange(data, (int) from, (int) to));
    }

    /** Delivers {@code limit} bytes, then fails like a dropped connection. */
    private static InputStream cutAfter(byte[] data, long from, int limit) {
        return new InputStream() {
            int sent;
            @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (sent >= limit) throw new java.net.SocketException("Connection reset");
                int n = Math.min(Math.min(len, 10_000), limit - sent);
                System.arraycopy(data, (int) from + sent, b, off, n);
                sent += n;
                return n;
            }
        };
    }

    @Test public void theSessionIdIsStableForTheSameClientFile() throws Exception {
        String key = UploadSessions.clientKey("a.mkv", 10, 42, "Trip/a.mkv");
        assertEquals(UploadSessions.sessionId(key), UploadSessions.sessionId(key));
        assertTrue(UploadSessions.validId(UploadSessions.sessionId(key)));
        assertNotEquals(UploadSessions.sessionId(key), UploadSessions.sessionId(UploadSessions.clientKey("a.mkv", 10, 43, "Trip/a.mkv")));
        assertNotEquals(UploadSessions.sessionId(key), UploadSessions.sessionId(UploadSessions.clientKey("a.mkv", 11, 42, "Trip/a.mkv")));
        assertNotEquals(UploadSessions.sessionId(key), UploadSessions.sessionId(UploadSessions.clientKey("a.mkv", 10, 42, "Other/a.mkv")));
        assertFalse(UploadSessions.validId("../../x"));
        assertFalse(UploadSessions.validId(null));

        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session first = sessions.start("a.mkv", 10, 42, "Trip/a.mkv");
        assertSame(first, sessions.start("a.mkv", 10, 42, "Trip/a.mkv"));
        assertEquals("the data file is preallocated to the full size", 10, first.data.length());
        assertTrue(first.data.getParentFile().getName().equals("uploads"));
    }

    @Test public void partsInAnyOrderAssembleTheFileAndKeepItsFolder() throws Exception {
        byte[] data = random(300_000, 1);
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session session = sessions.start("a.bin", data.length, 1000, "Trip/day 1/a.bin");
        sessions.write(session.id, 200_000, 100_000, part(data, 200_000, 300_000), pool);
        sessions.write(session.id, 0, 50_000, part(data, 0, 50_000), pool);
        List<long[]> missing = session.manifest.missing();
        assertEquals(1, missing.size());
        assertEquals(50_000, missing.get(0)[0]);
        assertEquals(200_000, missing.get(0)[1]);
        try { sessions.finish(session.id); fail("an incomplete upload must not finish"); }
        catch (UploadSessions.Failure expected) { assertEquals(409, expected.status); }
        assertEquals(300_000, sessions.write(session.id, 50_000, 150_000, part(data, 50_000, 200_000), pool));
        UploadSessions.Finished finished = sessions.finish(session.id);
        assertEquals("Trip/day 1/a.bin", finished.inboxId);
        assertEquals(new File(temporary.getRoot(), "received/Trip/day 1/a.bin").getCanonicalFile(), finished.file.getCanonicalFile());
        assertArrayEquals(data, Files.readAllBytes(finished.file.toPath()));
        assertEquals(1000, finished.file.lastModified());
        assertFalse("the manifest goes once the file is done", session.manifestFile.exists());
        assertFalse(session.data.exists());

        // The same folder path again lands beside it, numbered.
        UploadSessions.Session again = sessions.start("a.bin", 3, 2000, "Trip/day 1/a.bin");
        sessions.write(again.id, 0, 3, part(data, 0, 3), pool);
        assertEquals("Trip/day 1/a (2).bin", sessions.finish(again.id).inboxId);
    }

    @Test public void aSingleFileGetsTheInboxPrefixAndEmptyFilesFinishAtOnce() throws Exception {
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session empty = sessions.start("empty.txt", 0, 5, null);
        assertTrue(empty.manifest.complete());
        UploadSessions.Finished finished = sessions.finish(empty.id);
        assertEquals("edge-" + empty.id + "-empty.txt", finished.inboxId);
        assertEquals("empty.txt", finished.name);
        assertEquals("empty.txt", EdgeInbox.name(finished.inboxId));
        assertEquals(0, finished.file.length());
    }

    @Test public void resumesAfterAConnectionDropsMidRange() throws Exception {
        byte[] data = random(500_000, 2);
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session session = sessions.start("movie.mkv", data.length, 77, null);
        try {
            sessions.write(session.id, 0, data.length, cutAfter(data, 0, 123_456), pool);
            fail("a dropped connection must surface");
        } catch (IOException expected) { /* the client resumes */ }
        // What arrived before the drop is kept, and saved for a restart.
        assertEquals(123_456, session.manifest.completedBytes());
        sessions.closeAll();

        UploadSessions restarted = new UploadSessions(temporary.getRoot());
        UploadSessions.Session resumed = restarted.start("movie.mkv", data.length, 77, null);
        assertTrue(resumed.resumed);
        assertEquals(session.id, resumed.id);
        assertEquals(123_456, resumed.manifest.firstMissing());
        restarted.write(resumed.id, 123_456, data.length - 123_456, part(data, 123_456, data.length), pool);
        UploadSessions.Finished finished = restarted.finish(resumed.id);
        assertArrayEquals(data, Files.readAllBytes(finished.file.toPath()));
    }

    @Test public void aSessionIsFoundAgainAfterARestartByItsId() throws Exception {
        byte[] data = random(1000, 3);
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session session = sessions.start("x.bin", data.length, 9, "Docs/x.bin");
        sessions.write(session.id, 0, 400, part(data, 0, 400), pool);
        sessions.closeAll();
        UploadSessions restarted = new UploadSessions(temporary.getRoot());
        UploadSessions.Session found = restarted.find(session.id);
        assertNotNull(found);
        assertEquals("Docs/x.bin", found.relPath);
        assertEquals(400, found.manifest.completedBytes());
        restarted.write(session.id, 400, 600, part(data, 400, 1000), pool);
        assertEquals("Docs/x.bin", restarted.finish(session.id).inboxId);
        assertNull(restarted.find("0123456789abcdef0123456789abcdef"));
    }

    @Test public void damagedPartialDataStartsOver() throws Exception {
        byte[] data = random(10_000, 4);
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session session = sessions.start("y.bin", data.length, 1, null);
        sessions.write(session.id, 0, 5000, part(data, 0, 5000), pool);
        sessions.closeAll();
        // Someone truncated the partial file: its manifest no longer describes it.
        try (RandomAccessFile file = new RandomAccessFile(session.data, "rw")) { file.setLength(100); }
        UploadSessions restarted = new UploadSessions(temporary.getRoot());
        UploadSessions.Session fresh = restarted.start("y.bin", data.length, 1, null);
        assertFalse(fresh.resumed);
        assertEquals(0, fresh.manifest.completedBytes());
        assertEquals(data.length, fresh.data.length());
    }

    @Test public void refusesBadRangesFullStorageAndUnknownIds() throws Exception {
        UploadSessions sessions = new UploadSessions(temporary.getRoot(), folder -> 1000);
        try { sessions.start("big.bin", 5000, 1, null); fail(); }
        catch (UploadSessions.Failure full) { assertEquals(507, full.status); }
        UploadSessions.Session session = sessions.start("ok.bin", 100, 1, null);
        try { sessions.write(session.id, 90, 20, part(new byte[20], 0, 20), pool); fail(); }
        catch (UploadSessions.Failure outside) { assertEquals(416, outside.status); }
        try { sessions.write("ffffffffffffffffffffffffffffffff", 0, 1, part(new byte[1], 0, 1), pool); fail(); }
        catch (UploadSessions.Failure unknown) { assertEquals(404, unknown.status); }
        try { sessions.start("x", 1, 1, "../.."); fail(); }
        catch (UploadSessions.Failure invalid) { assertEquals(400, invalid.status); }
        try { sessions.write(session.id, 0, 100, part(new byte[50], 0, 50), pool); fail(); }
        catch (UploadSessions.Failure early) { assertEquals(400, early.status); }
        assertEquals(50, session.manifest.completedBytes());
    }

    @Test public void cancelAndPruneRemoveTheData() throws Exception {
        UploadSessions sessions = new UploadSessions(temporary.getRoot());
        UploadSessions.Session session = sessions.start("c.bin", 100, 1, null);
        assertTrue(sessions.cancel(session.id));
        assertFalse(session.data.exists());
        assertFalse(session.manifestFile.exists());
        assertFalse(sessions.cancel(session.id));
        UploadSessions.Session old = sessions.start("old.bin", 100, 1, null);
        sessions.closeAll();
        assertEquals(0, new UploadSessions(temporary.getRoot()).prune(System.currentTimeMillis()));
        assertEquals(2, new UploadSessions(temporary.getRoot()).prune(System.currentTimeMillis() + UploadSessions.STALE_MS + 60_000));
        assertFalse(old.data.exists());
    }
}
