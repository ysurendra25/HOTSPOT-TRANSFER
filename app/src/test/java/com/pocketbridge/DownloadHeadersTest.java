package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Content-Disposition building for ?download=1 (attachment) and playback (inline). */
public class DownloadHeadersTest {

    @Test public void downloadQueryValues() {
        assertTrue(DownloadHeaders.wantsDownload("1"));
        assertTrue(DownloadHeaders.wantsDownload("true"));
        assertTrue(DownloadHeaders.wantsDownload("YES"));
        assertFalse(DownloadHeaders.wantsDownload(null));
        assertFalse(DownloadHeaders.wantsDownload(""));
        assertFalse(DownloadHeaders.wantsDownload("0"));
    }

    @Test public void attachmentVersusInline() {
        assertEquals("attachment; filename=\"clip.mp4\"; filename*=UTF-8''clip.mp4",
                DownloadHeaders.contentDisposition("clip.mp4", true));
        assertEquals("inline; filename=\"clip.mp4\"; filename*=UTF-8''clip.mp4",
                DownloadHeaders.contentDisposition("clip.mp4", false));
        assertEquals("Content-Disposition: attachment; filename=\"a.txt\"; filename*=UTF-8''a.txt\r\n",
                DownloadHeaders.headerLine("a.txt", true));
    }

    @Test public void spacesAndReservedCharactersArePercentEncoded() {
        assertEquals("My%20Video%20%281%29.mp4", DownloadHeaders.rfc5987("My Video (1).mp4"));
        assertEquals("a%2Ab%27c%25d%3Be%2Cf", DownloadHeaders.rfc5987("a*b'c%d;e,f"));
        // attr-char stays as is
        assertEquals("A-z_0.9!#$&+^`|~", DownloadHeaders.rfc5987("A-z_0.9!#$&+^`|~"));
    }

    @Test public void unicodeNamesUseUtf8AndAnAsciiFallback() {
        String name = "café ☕ 日本.mp4";
        assertEquals("caf%C3%A9%20%E2%98%95%20%E6%97%A5%E6%9C%AC.mp4", DownloadHeaders.rfc5987(name));
        assertEquals("caf_ _ __.mp4", DownloadHeaders.asciiFallback(name));
        // Emoji outside the BMP is one replacement, not two.
        assertEquals("_.jpg", DownloadHeaders.asciiFallback("\uD83D\uDE00.jpg"));
        assertEquals("%F0%9F%98%80.jpg", DownloadHeaders.rfc5987("\uD83D\uDE00.jpg"));
    }

    @Test public void headerInjectionAndQuotesAreNeutralised() {
        String value = DownloadHeaders.contentDisposition("evil\"\r\nSet-Cookie: x=1\\.txt", true);
        assertFalse(value.contains("\r"));
        assertFalse(value.contains("\n"));
        assertEquals("attachment; filename=\"evil___Set-Cookie: x=1_.txt\"; filename*=UTF-8''evil%22__Set-Cookie%3A%20x%3D1%5C.txt", value);
        for (char c : value.toCharArray()) assertTrue("ASCII only", c < 0x7f && c >= 0x20);
    }

    @Test public void emptyNamesFallBack() {
        assertEquals("download", DownloadHeaders.asciiFallback(null));
        assertEquals("download", DownloadHeaders.asciiFallback("  "));
        assertEquals("download", DownloadHeaders.asciiFallback(".."));
        assertEquals("download", DownloadHeaders.rfc5987(""));
    }

    @Test public void etagChangesWithTheFileAndGuardsIfRange() {
        String etag = DownloadHeaders.etag("42", 1000, 7);
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""));
        assertEquals(etag, DownloadHeaders.etag("42", 1000, 7));
        assertNotEquals(etag, DownloadHeaders.etag("42", 1001, 7));
        assertNotEquals(etag, DownloadHeaders.etag("42", 1000, 8));
        assertTrue(DownloadHeaders.ifRangeMatches(null, etag));
        assertTrue(DownloadHeaders.ifRangeMatches(etag, etag));
        assertFalse(DownloadHeaders.ifRangeMatches("\"stale\"", etag));
    }
}
