package com.pocketbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScreenCaptureSizingTest {
    @Test public void directCaptureKeepsThePhonesNativePortraitResolution() {
        assertArrayEquals(new int[]{1080, 2460}, ScreenCaptureSizing.evenSize(1080, 2460));
    }

    @Test public void directCaptureKeepsNativeLandscapeResolutionAfterRotation() {
        assertArrayEquals(new int[]{2460, 1080}, ScreenCaptureSizing.evenSize(2460, 1080));
    }

    @Test public void jpegFallbackScalesTheLongEdgeWithoutUpscaling() {
        int[] portrait = ScreenCaptureSizing.fitWithinMaxEdge(1080, 2460, 1280);
        int[] small = ScreenCaptureSizing.fitWithinMaxEdge(640, 480, 1280);

        assertTrue(Math.max(portrait[0], portrait[1]) <= 1280);
        assertTrue(Math.abs((double) portrait[0] / portrait[1] - 1080d / 2460d) < 0.002d);
        assertArrayEquals(new int[]{640, 480}, small);
    }

    @Test public void oddDimensionsAreRoundedDownToEvenPixels() {
        assertArrayEquals(new int[]{1080, 2460}, ScreenCaptureSizing.evenSize(1081, 2461));
    }
}
