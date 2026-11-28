package com.pocketbridge;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransferPlanTest {
    private static final long MIB = TransferPlan.MIB;

    @Test public void splitCoversEveryByteExactlyOnce() {
        long total = 100 * MIB + 12345;
        List<long[]> ranges = TransferPlan.split(0, total, 16 * MIB);
        assertEquals(7, ranges.size());
        long at = 0;
        for (long[] range : ranges) {
            assertEquals(at, range[0]);
            assertTrue(range[1] - range[0] <= 16 * MIB);
            at = range[1];
        }
        assertEquals(total, at);
        assertTrue(TransferPlan.split(5, 5, MIB).isEmpty());
        List<long[]> tail = TransferPlan.split(10, 25, 10);
        assertEquals(2, tail.size());
        assertEquals(20, tail.get(1)[0]);
        assertEquals(25, tail.get(1)[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void splitRefusesAZeroChunk() { TransferPlan.split(0, 10, 0); }

    @Test public void rangeHeadersAreInclusiveAndContentRangeIsParsed() {
        assertEquals("bytes=0-1048575", TransferPlan.rangeHeader(0, MIB));
        assertEquals("bytes=4294967296-4294967296", TransferPlan.rangeHeader(4294967296L, 4294967297L));
        assertEquals(5_000_000_000L, TransferPlan.totalFromContentRange("bytes 0-0/5000000000"));
        assertEquals(-1, TransferPlan.totalFromContentRange("bytes 0-9/*"));
        assertEquals(-1, TransferPlan.totalFromContentRange(null));
        assertEquals(4294967296L, TransferPlan.startFromContentRange("bytes 4294967296-4294967300/5000000000"));
        assertEquals(-1, TransferPlan.startFromContentRange("bytes -5/10"));
        assertEquals(-1, TransferPlan.startFromContentRange(null));
    }

    @Test public void connectionsStartFromSize() {
        assertEquals(1, TransferPlan.initialConnections(MIB));
        assertEquals(1, TransferPlan.initialConnections(8 * MIB - 1));
        assertEquals(2, TransferPlan.initialConnections(8 * MIB));
        assertEquals(4, TransferPlan.initialConnections(4096 * MIB));
    }

    @Test public void chunksAreClampedToWholeMebibytes() {
        assertEquals(MIB, TransferPlan.initialChunk(0, 1));
        assertEquals(MIB, TransferPlan.initialChunk(3 * MIB, 1));
        assertEquals(16 * MIB, TransferPlan.initialChunk(10_000 * MIB, 4));
        assertEquals(4 * MIB, TransferPlan.initialChunk(64 * MIB, 2));
        assertEquals(0, TransferPlan.clampChunk(5 * MIB + 3) % MIB);
    }

    @Test public void chunkSizeAdaptsToHowLongTheLastChunkTook() {
        long fast = 200_000_000L, steady = 1_000_000_000L, slow = 3_000_000_000L;
        assertEquals(8 * MIB, TransferPlan.adaptChunk(4 * MIB, 4 * MIB, fast));
        assertEquals(4 * MIB, TransferPlan.adaptChunk(4 * MIB, 4 * MIB, steady));
        assertEquals(2 * MIB, TransferPlan.adaptChunk(4 * MIB, 4 * MIB, slow));
        assertEquals(16 * MIB, TransferPlan.adaptChunk(16 * MIB, 16 * MIB, fast));
        assertEquals(MIB, TransferPlan.adaptChunk(MIB, MIB, slow));
        assertEquals(4 * MIB, TransferPlan.adaptChunk(4 * MIB, 0, fast));
        long chunk = MIB;
        for (int i = 0; i < 10; i++) chunk = TransferPlan.adaptChunk(chunk, chunk, fast);
        assertEquals(TransferPlan.MAX_CHUNK, chunk);
    }

    @Test public void concurrencyClimbsWhileItHelpsThenHolds() {
        TransferPlan.Concurrency c = new TransferPlan.Concurrency(2, 2, 6);
        assertEquals(3, c.observe(10));
        assertEquals(4, c.observe(13));   // +30%: one more
        assertEquals(3, c.observe(11));   // worse: step back and hold
        assertEquals(3, c.observe(11.5));
        assertEquals(2, c.observe(5));    // big drop re-opens downwards
        assertEquals(2, c.observe(0));    // no measurement, no change
    }

    @Test public void concurrencyNeverLeavesItsBounds() {
        TransferPlan.Concurrency c = new TransferPlan.Concurrency(9, 2, 6);
        assertEquals(6, c.current());
        double rate = 10;
        for (int i = 0; i < 10; i++) { rate *= 2; c.observe(rate); }
        assertEquals(6, c.current());
        TransferPlan.Concurrency low = new TransferPlan.Concurrency(0, 2, 6);
        assertEquals(2, low.current());
        for (int i = 0; i < 10; i++) low.observe(1.0 / (i + 1));
        assertTrue(low.current() >= 2);
    }
}
