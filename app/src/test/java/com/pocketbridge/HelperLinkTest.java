package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HelperLinkTest {
    @Test public void parsesThePrintedLanLink() {
        HelperLink link = HelperLink.parse("http://192.168.43.42:7070/?t=abc23");
        assertEquals("192.168.43.42", link.host);
        assertEquals(7070, link.port);
        assertEquals("abc23", link.token);
        assertFalse(link.isLoopback());
    }

    @Test public void acceptsWhatPeopleActuallyPaste() {
        for (String pasted : new String[]{
                "  http://192.168.43.42:7070/?t=abc23  ",
                "\"http://192.168.43.42:7070/?t=abc23\"",
                "192.168.43.42:7070/?t=abc23",
                "2026/11/19 10:00:00 [pc-remote] LAN: http://192.168.43.42:7070/?t=abc23",
                "<http://192.168.43.42:7070/?t=abc23>"}) {
            HelperLink link = HelperLink.parse(pasted);
            assertEquals(pasted, "192.168.43.42", link.host);
            assertEquals(pasted, "abc23", link.token);
        }
    }

    @Test public void defaultsToTheHelperPort() {
        assertEquals(7070, HelperLink.parse("http://10.0.0.5/?t=x").port);
    }

    @Test public void explainsAMissingCodeOrWrongScheme() {
        expectMessage("http://192.168.43.42:7070/", "access code");
        expectMessage("https://192.168.43.42:7070/?t=x", "http://");
        expectMessage("", "Paste the link");
        assertNull(HelperLink.tryParse("not a link at all"));
    }

    private static void expectMessage(String raw, String fragment) {
        try {
            HelperLink.parse(raw);
            fail("expected a failure for " + raw);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
        }
    }

    @Test public void recognisesTheLaptopsOwnAddress() {
        assertTrue(HelperLink.parse("http://127.0.0.1:7070/?t=x").isLoopback());
        assertTrue(HelperLink.parse("http://localhost:7070/?t=x").isLoopback());
    }

    @Test public void buildsApiUrlsWithTheCodeLast() {
        HelperLink link = HelperLink.parse("http://192.168.43.42:7070/?t=a%20b%26c");
        assertEquals("http://192.168.43.42:7070/power?t=a%20b%26c", link.url("/power"));
        assertEquals("http://192.168.43.42:7070/files?offset=500&limit=500&t=a%20b%26c",
                link.url("/files", "offset", "500", "limit", "500"));
    }

    @Test public void keepsTheHelpersAlreadyEscapedFilePaths() {
        HelperLink link = HelperLink.parse("http://192.168.43.42:7070/?t=tok");
        // The helper escapes each segment; spaces, % and unicode must not be escaped twice.
        String url = link.url("/file/Trips/100%25%20done%20%D1%84%D0%BE%D1%82%D0%BE.mp4");
        assertEquals("http://192.168.43.42:7070/file/Trips/100%25%20done%20%D1%84%D0%BE%D1%82%D0%BE.mp4?t=tok", url);
    }

    @Test public void acceptsOnlyTheAddressFormsPrintedByTheWindowsHelper() {
        for (String host : new String[]{"10.0.0.2", "172.16.0.1", "172.31.255.2", "192.168.43.42"}) {
            HelperLink.parse("http://" + host + ":7070/?t=tok").requireLanAddress();
        }
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "0.0.0.0", "example.org",
                "8.8.8.8", "172.15.1.2", "172.32.1.2", "192.167.1.1", "010.0.0.1", "10.0.0.300"}) {
            try {
                HelperLink.parse("http://" + host + ":7070/?t=tok").requireLanAddress();
                fail(host);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
        }
    }
}
