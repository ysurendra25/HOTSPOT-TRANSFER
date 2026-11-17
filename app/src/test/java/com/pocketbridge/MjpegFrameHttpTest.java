package com.pocketbridge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class MjpegFrameHttpTest {
    @Test public void writesMultipartBoundaryAndExactFrameLength() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MediaHttpServer.writeMjpegFrame(output, new byte[]{1, 2, 3, 4});
        String response = new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(response.startsWith("--pocketbridge-frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n"));
        assertTrue(response.endsWith("\r\n"));
    }
}
