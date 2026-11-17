package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which library filter the phone UI and the web page ask for, and what it means. */
public class MediaRepositoryKindTest {

    @Test public void acceptsSingularAndPluralFilters() {
        assertEquals("images", MediaRepository.normalizeKind("image"));
        assertEquals("images", MediaRepository.normalizeKind("images"));
        assertEquals("images", MediaRepository.normalizeKind("Photos"));
        assertEquals("videos", MediaRepository.normalizeKind("video"));
        assertEquals("videos", MediaRepository.normalizeKind("videos"));
        assertEquals("audio", MediaRepository.normalizeKind("audio"));
        assertEquals("audio", MediaRepository.normalizeKind("music"));
        assertEquals("files", MediaRepository.normalizeKind("file"));
        assertEquals("files", MediaRepository.normalizeKind("files"));
    }

    @Test public void unknownFiltersFallBackToEverything() {
        assertEquals("all", MediaRepository.normalizeKind("all"));
        assertEquals("all", MediaRepository.normalizeKind(""));
        assertEquals("all", MediaRepository.normalizeKind(null));
        assertEquals("all", MediaRepository.normalizeKind("something-else"));
    }

    @Test public void classifyItemsWithoutAMimeType() {
        assertEquals("image", MediaTypeClassifier.kind("application/octet-stream", "IMG_0001.HEIC", "file"));
        assertEquals("video", MediaTypeClassifier.kind("", "clip.MKV", "file"));
        assertEquals("audio", MediaTypeClassifier.kind("application/octet-stream", "song.opus", "file"));
        assertEquals("file", MediaTypeClassifier.kind("application/pdf", "doc.pdf", "file"));
    }

    @Test public void mimeTypeRepairsGenericProviderValues() {
        assertEquals("video/x-matroska", MediaTypeClassifier.mimeType("clip.mkv", "application/octet-stream"));
        assertEquals("audio/ogg", MediaTypeClassifier.mimeType("song.opus", "application/octet-stream"));
        assertEquals("image/avif", MediaTypeClassifier.mimeType("pic.avif", "application/x-unknown"));
        assertEquals("image/png", MediaTypeClassifier.mimeType("pic.png", "image/png"));
    }

    @Test public void byteRangesCoverTheAwkwardCases() {
        MediaHttpServer.ByteRange single = MediaHttpServer.ByteRange.parse("bytes=0-0", 100);
        assertEquals(0, single.start);
        assertEquals(0, single.end);
        assertTrue(single.partial);

        MediaHttpServer.ByteRange clamped = MediaHttpServer.ByteRange.parse("bytes=90-1000", 100);
        assertEquals(90, clamped.start);
        assertEquals(99, clamped.end);

        assertTrue(MediaHttpServer.ByteRange.parse("bytes=-0", 100).unsatisfiable);
        assertTrue(MediaHttpServer.ByteRange.parse("bytes=50-10", 100).unsatisfiable);

        // Several ranges at once and malformed headers fall back to the whole file.
        MediaHttpServer.ByteRange multi = MediaHttpServer.ByteRange.parse("bytes=0-10,20-30", 100);
        assertFalse(multi.partial);
        assertEquals(99, multi.end);

        MediaHttpServer.ByteRange junk = MediaHttpServer.ByteRange.parse("items=0-10", 100);
        assertFalse(junk.partial);
        assertEquals(99, junk.end);
    }
}
