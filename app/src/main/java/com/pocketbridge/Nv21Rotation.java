package com.pocketbridge;

/** Rotates an even-sized NV21 frame without decoding and recompressing a JPEG. */
final class Nv21Rotation {
    static final class Frame {
        final byte[] data;
        final int width;
        final int height;
        Frame(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private Nv21Rotation() { }

    static Frame rotate(byte[] source, int width, int height, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        if (rotation == 0) return new Frame(source, width, height);
        if (rotation != 90 && rotation != 180 && rotation != 270)
            throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270 degrees");
        if ((width & 1) != 0 || (height & 1) != 0 || source.length < width * height * 3 / 2)
            throw new IllegalArgumentException("invalid NV21 frame dimensions");
        int outWidth = rotation == 180 ? width : height;
        int outHeight = rotation == 180 ? height : width;
        byte[] output = new byte[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx;
                int dy;
                if (rotation == 90) { dx = height - 1 - y; dy = x; }
                else if (rotation == 180) { dx = width - 1 - x; dy = height - 1 - y; }
                else { dx = y; dy = width - 1 - x; }
                output[dy * outWidth + dx] = source[y * width + x];
            }
        }
        int chromaOffset = width * height;
        int outChromaOffset = outWidth * outHeight;
        for (int cy = 0; cy < height / 2; cy++) {
            for (int cx = 0; cx < width / 2; cx++) {
                int dx;
                int dy;
                if (rotation == 90) { dx = height / 2 - 1 - cy; dy = cx; }
                else if (rotation == 180) { dx = width / 2 - 1 - cx; dy = height / 2 - 1 - cy; }
                else { dx = cy; dy = width / 2 - 1 - cx; }
                int src = chromaOffset + cy * width + cx * 2;
                int dst = outChromaOffset + dy * outWidth + dx * 2;
                output[dst] = source[src];
                output[dst + 1] = source[src + 1];
            }
        }
        return new Frame(output, outWidth, outHeight);
    }
}
