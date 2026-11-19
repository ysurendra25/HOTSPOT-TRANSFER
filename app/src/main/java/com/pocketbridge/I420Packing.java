package com.pocketbridge;

import java.nio.ByteBuffer;

/**
 * Packs WebRTC's I420 planes into the NV21 layout YuvImage compresses.
 *
 * Each plane row may be wider than the picture (the stride carries padding).
 * Rows are copied one bulk read at a time, honouring the stride; the old
 * byte-by-byte copy took long enough at native resolution that the JPEG
 * fallback fell seconds behind. Plain Java so the stride handling is tested.
 */
final class I420Packing {
    private I420Packing() { }

    static byte[] toNv21(ByteBuffer yPlane, int strideY, ByteBuffer uPlane, int strideU,
                         ByteBuffer vPlane, int strideV, int width, int height) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("I420 frames need positive, even dimensions: " + width + "x" + height);
        }
        int ySize = width * height;
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        byte[] nv21 = new byte[ySize + ySize / 2];
        ByteBuffer y = yPlane.duplicate();
        for (int row = 0; row < height; row++) {
            y.position(row * strideY);
            y.get(nv21, row * width, width);
        }
        ByteBuffer u = uPlane.duplicate();
        ByteBuffer v = vPlane.duplicate();
        byte[] uRow = new byte[chromaWidth];
        byte[] vRow = new byte[chromaWidth];
        int out = ySize;
        for (int row = 0; row < chromaHeight; row++) {
            u.position(row * strideU);
            u.get(uRow, 0, chromaWidth);
            v.position(row * strideV);
            v.get(vRow, 0, chromaWidth);
            for (int col = 0; col < chromaWidth; col++) {
                nv21[out++] = vRow[col];
                nv21[out++] = uRow[col];
            }
        }
        return nv21;
    }
}
