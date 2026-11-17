package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaHttpServerByteRangeTest {
    @Test public void servesFullFileWhenBrowserSendsNoRange() {
        MediaHttpServer.ByteRange range = MediaHttpServer.ByteRange.parse(null, 100);
        assertEquals(0, range.start);
        assertEquals(99, range.end);
        assertFalse(range.partial);
        assertFalse(range.unsatisfiable);
    }

    @Test public void supportsBrowserSeekAndSuffixRanges() {
        MediaHttpServer.ByteRange seek = MediaHttpServer.ByteRange.parse("bytes=50-", 100);
        assertEquals(50, seek.start);
        assertEquals(99, seek.end);
        assertTrue(seek.partial);

        MediaHttpServer.ByteRange suffix = MediaHttpServer.ByteRange.parse("bytes=-20", 100);
        assertEquals(80, suffix.start);
        assertEquals(99, suffix.end);
        assertTrue(suffix.partial);
    }

    @Test public void rejectsRangesBeyondFileEnd() {
        MediaHttpServer.ByteRange range = MediaHttpServer.ByteRange.parse("bytes=100-", 100);
        assertTrue(range.unsatisfiable);
    }
}
