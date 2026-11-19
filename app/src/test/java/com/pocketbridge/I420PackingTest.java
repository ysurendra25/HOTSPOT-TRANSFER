package com.pocketbridge;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;

public class I420PackingTest {
    /** Rows carry padding beyond the picture width; it must never reach the image. */
    @Test public void honoursRowStrideAndInterleavesChromaAsVu() {
        int width = 4, height = 2, strideY = 6, strideC = 4;
        ByteBuffer y = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{10, 11, 99, 99});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{20, 21, 99, 99});
        byte[] nv21 = I420Packing.toNv21(y, strideY, u, strideC, v, strideC, width, height);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11}, nv21);
    }

    @Test public void leavesTheSourceBuffersUntouched() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{5});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{6});
        I420Packing.toNv21(y, 2, u, 1, v, 1, 2, 2);
        org.junit.Assert.assertEquals(0, y.position());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesOddSizes() {
        I420Packing.toNv21(ByteBuffer.allocate(9), 3, ByteBuffer.allocate(4), 2, ByteBuffer.allocate(4), 2, 3, 3);
    }
}
