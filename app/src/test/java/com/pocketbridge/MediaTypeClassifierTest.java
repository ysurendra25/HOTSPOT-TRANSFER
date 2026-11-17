package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaTypeClassifierTest {
    @Test public void identifiesGenericVideoFromExtension() {
        assertEquals("video", MediaTypeClassifier.kind(
                MediaTypeClassifier.mimeType("Trip.MP4", "application/octet-stream"), "Trip.MP4", "file"));
        assertEquals("video/mp4", MediaTypeClassifier.mimeType("Trip.MP4", "application/octet-stream"));
        assertEquals("video/mp4", MediaTypeClassifier.mimeType("Trip.mp4", "application/x-unknown"));
    }

    @Test public void identifiesGenericPhotosAndPreservesReportedMime() {
        assertEquals("image", MediaTypeClassifier.kind("image/heic", "IMG_1234", "file"));
        assertEquals("image/heic", MediaTypeClassifier.mimeType("IMG_1234.HEIC", "application/octet-stream"));
        assertEquals("image/jpeg", MediaTypeClassifier.mimeType("photo.jpg", "image/jpeg"));
    }

    @Test public void unknownItemsRemainFiles() {
        assertEquals("file", MediaTypeClassifier.kind("application/octet-stream", "archive.bin", "file"));
        assertEquals("application/octet-stream", MediaTypeClassifier.mimeType("archive.bin", "application/octet-stream"));
    }
}
