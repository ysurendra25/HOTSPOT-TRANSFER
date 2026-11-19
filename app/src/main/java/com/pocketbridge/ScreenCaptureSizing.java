package com.pocketbridge;

/** Dimension helpers shared by the full-resolution WebRTC path and JPEG fallback. */
final class ScreenCaptureSizing {
    private ScreenCaptureSizing() { }

    static int[] evenSize(int width, int height) {
        return new int[]{evenAtMost(width), evenAtMost(height)};
    }

    static int[] fitWithinMaxEdge(int width, int height, int maxEdge) {
        if (maxEdge < 2) throw new IllegalArgumentException("maxEdge must be at least 2");
        int[] source = evenSize(width, height);
        float scale = Math.min(1f, (float) maxEdge / Math.max(source[0], source[1]));
        int scaledWidth = Math.round(source[0] * scale / 2f) * 2;
        int scaledHeight = Math.round(source[1] * scale / 2f) * 2;
        return evenSize(scaledWidth, scaledHeight);
    }

    private static int evenAtMost(int value) {
        return Math.max(2, value / 2 * 2);
    }
}
