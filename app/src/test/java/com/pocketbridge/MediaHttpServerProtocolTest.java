package com.pocketbridge;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** HTTP framing, upload limits and ZIP entry naming for the phone web server. */
public class MediaHttpServerProtocolTest {

    private static String head(int status, long length) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MediaHttpServer.writeHead(output, status, "text/plain", length, null, null);
        return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    @Test public void usesCorrectReasonPhrases() {
        assertEquals("OK", MediaHttpServer.reasonPhrase(200));
        assertEquals("Created", MediaHttpServer.reasonPhrase(201));
        assertEquals("Partial Content", MediaHttpServer.reasonPhrase(206));
        assertEquals("Bad Request", MediaHttpServer.reasonPhrase(400));
        assertEquals("Payload Too Large", MediaHttpServer.reasonPhrase(413));
        assertEquals("Range Not Satisfiable", MediaHttpServer.reasonPhrase(416));
        assertEquals("Internal Server Error", MediaHttpServer.reasonPhrase(500));
    }

    @Test public void unknownStatusesStillProduceAValidLine() {
        assertFalse(MediaHttpServer.reasonPhrase(418).isEmpty());
        assertEquals("Server Error", MediaHttpServer.reasonPhrase(599));
    }

    @Test public void statusLineAndLengthAreWritten() throws Exception {
        String response = head(201, 12);
        assertTrue(response.startsWith("HTTP/1.1 201 Created\r\n"));
        assertTrue(response.contains("Content-Length: 12\r\n"));
        assertTrue(response.contains("Connection: close\r\n"));
        assertTrue(response.contains("X-Content-Type-Options: nosniff\r\n"));
        assertTrue(response.endsWith("\r\n\r\n"));
    }

    @Test public void streamingResponsesOmitContentLength() throws Exception {
        String response = head(200, -1);
        assertFalse(response.contains("Content-Length"));
    }

    @Test public void uploadsAreNotCappedByAnArbitraryFileSize() {
        assertTrue(MediaHttpServer.uploadAllowed(1));
        assertTrue(MediaHttpServer.uploadAllowed(15L * 1024 * 1024 * 1024));
        assertTrue(MediaHttpServer.uploadAllowed(16L * 1024 * 1024 * 1024));
        assertTrue(MediaHttpServer.uploadAllowed(Long.MAX_VALUE));
        assertFalse(MediaHttpServer.uploadAllowed(0));
        assertFalse(MediaHttpServer.uploadAllowed(-5));
    }

    @Test public void storageCheckAllowsExactFitAndRejectsWhenShort() {
        long movieSize = 15L * 1024 * 1024 * 1024;
        assertTrue(MediaHttpServer.hasEnoughStorage(movieSize, movieSize));
        assertFalse(MediaHttpServer.hasEnoughStorage(movieSize - 1, movieSize));
        assertFalse(MediaHttpServer.hasEnoughStorage(Long.MAX_VALUE, 0));
    }

    @Test public void zipEntriesAreMadeSafeAndUnique() {
        Set<String> used = new HashSet<>();
        assertEquals("photo.jpg", MediaHttpServer.uniqueEntryName("photo.jpg", used));
        assertEquals("photo (2).jpg", MediaHttpServer.uniqueEntryName("photo.jpg", used));
        assertEquals("photo (3).jpg", MediaHttpServer.uniqueEntryName("photo.jpg", used));
    }

    @Test public void zipEntryNamesCannotEscapeTheFolder() {
        assertEquals("a_b.txt", MediaHttpServer.uniqueEntryName("a/b.txt", new HashSet<>()));
        assertEquals("a_b.txt", MediaHttpServer.uniqueEntryName("a\\b.txt", new HashSet<>()));
        assertEquals("file", MediaHttpServer.uniqueEntryName("..", new HashSet<>()));
        assertEquals("file", MediaHttpServer.uniqueEntryName(null, new HashSet<>()));
        assertEquals("file", MediaHttpServer.uniqueEntryName("   ", new HashSet<>()));
    }
}
