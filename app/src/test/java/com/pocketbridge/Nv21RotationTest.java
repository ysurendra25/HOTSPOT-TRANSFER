package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class Nv21RotationTest {
    @Test public void rotates90ClockwiseWithoutChangingChromaPairs() {
        byte[] source = {1, 2, 3, 4, 5, 6};
        Nv21Rotation.Frame rotated = Nv21Rotation.rotate(source, 2, 2, 90);
        assertEquals(2, rotated.width);
        assertEquals(2, rotated.height);
        assertArrayEquals(new byte[]{3, 1, 4, 2, 5, 6}, rotated.data);
    }

    @Test public void rotatesLandscapeToPortraitAndKeepsChromaOrder() {
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8, 21, 22, 23, 24};
        Nv21Rotation.Frame rotated = Nv21Rotation.rotate(source, 4, 2, 90);
        assertEquals(2, rotated.width);
        assertEquals(4, rotated.height);
        assertArrayEquals(new byte[]{5, 1, 6, 2, 7, 3, 8, 4, 21, 22, 23, 24}, rotated.data);
    }

    @Test public void zeroRotationReturnsSourceFrame() {
        byte[] source = {1, 2, 3, 4, 5, 6};
        Nv21Rotation.Frame rotated = Nv21Rotation.rotate(source, 2, 2, 0);
        assertEquals(2, rotated.width);
        assertEquals(2, rotated.height);
        assertEquals(source, rotated.data);
    }
}
