package com.pocketbridge;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class QrTransferCodecTest {
    @Test public void textIsFramedAndRoundTripsAcrossMultipleQrs() {
        String text = "hello 🌿 ".repeat(100);
        List<String> frames = QrTransferCodec.textFrames("test-id", text);
        assertEquals(2, frames.size());
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (String value : frames) {
            QrTransferCodec.Frame frame = QrTransferCodec.parse(value);
            assertNotNull(frame);
            assertEquals("test-id", frame.id);
            joined.write(frame.bytes, 0, frame.bytes.length);
        }
        assertEquals(text, new String(joined.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void fileChunksPreserveNameMimeAndBinaryBytes() {
        byte[] bytes = new byte[2501];
        new java.util.Random(42).nextBytes(bytes);
        List<String> frames = QrTransferCodec.fileFrames("file-id", "Movie (1).mkv", "video/x-matroska", bytes);
        assertEquals(3, frames.size());
        java.io.ByteArrayOutputStream assembled = new java.io.ByteArrayOutputStream();
        for (String value : frames) {
            QrTransferCodec.Frame frame = QrTransferCodec.parse(value);
            assertNotNull(frame);
            assertEquals("Movie (1).mkv", frame.name);
            assertEquals("video/x-matroska", frame.mime);
            assembled.write(frame.bytes, 0, frame.bytes.length);
        }
        org.junit.Assert.assertArrayEquals(bytes, assembled.toByteArray());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLargeQrTransfers() {
        QrTransferCodec.fileFrames("large", "movie.mp4", "video/mp4", new byte[QrTransferCodec.MAX_BYTES + 1]);
    }
}
