package com.pocketbridge;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BufferPoolTest {
    @Test public void reusesBuffersAndNeverMakesMoreThanItsCapacity() throws Exception {
        BufferPool pool = new BufferPool(1024, 2, false);
        ByteBuffer a = pool.acquire(10), b = pool.acquire(10);
        assertEquals(2, pool.created());
        assertFalse(a.isDirect());
        a.put((byte) 7);
        pool.release(a);
        ByteBuffer again = pool.acquire(10);
        assertSame(a, again);
        assertEquals("a reused buffer comes back cleared", 0, again.position());
        assertEquals(1024, again.remaining());
        assertEquals(2, pool.created());
        pool.release(b);
        pool.release(again);
        assertEquals(2, pool.idle());
    }

    @Test public void waitsForABufferThenGivesUp() throws Exception {
        BufferPool pool = new BufferPool(16, 1, true);
        ByteBuffer only = pool.acquire(10);
        assertTrue(only.isDirect());
        try { pool.acquire(50); fail("an exhausted pool must time out"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Too many transfers")); }
        AtomicReference<ByteBuffer> got = new AtomicReference<>();
        Thread waiter = new Thread(() -> { try { got.set(pool.acquire(5000)); } catch (IOException ignored) { } });
        waiter.start();
        Thread.sleep(100);
        pool.release(only);
        waiter.join(5000);
        assertNotNull("a waiting caller gets the released buffer", got.get());
        assertEquals(1, pool.created());
    }

    @Test public void foreignBuffersAreNotAdopted() throws Exception {
        BufferPool pool = new BufferPool(16, 2, false);
        pool.release(ByteBuffer.allocate(32));
        pool.release(ByteBuffer.allocateDirect(16));
        pool.release(null);
        assertEquals(0, pool.idle());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAnEmptyPool() { new BufferPool(0, 1, false); }
}
