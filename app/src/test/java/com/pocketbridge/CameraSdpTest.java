package com.pocketbridge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Host-only ICE, hardware codec order and the phone↔phone link text. */
public class CameraSdpTest {
    private static final String OFFER = String.join("\r\n",
            "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:96 VP8/90000", "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96",
            "a=rtpmap:102 H264/90000", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:103 rtx/90000", "a=fmtp:103 apt=102",
            "a=candidate:1 1 udp 2122260223 192.168.43.20 50000 typ host generation 0",
            "a=candidate:2 1 udp 2122260223 abcd.local 50001 typ host generation 0",
            "a=candidate:3 1 tcp 1518280447 192.168.43.20 9 typ host tcptype active",
            "a=candidate:4 1 udp 1686052607 1.2.3.4 50000 typ srflx raddr 192.168.43.20 rport 50000",
            "a=candidate:5 1 udp 41885439 5.6.7.8 3478 typ relay raddr 0.0.0.0 rport 0",
            "");

    @Test public void keepsOnlyUdpHostCandidates() {
        String sdp = CameraSdp.hostCandidatesOnly(OFFER);
        assertTrue(sdp.contains("192.168.43.20 50000 typ host"));
        assertTrue("browser mDNS host candidates stay", sdp.contains("abcd.local"));
        assertFalse(sdp.contains("tcptype"));
        assertFalse(sdp.contains("typ srflx"));
        assertFalse(sdp.contains("typ relay"));
        assertTrue(sdp.contains("\r\n"));
    }

    @Test public void remoteOfferPutsH264FirstWithCameraBitrates() {
        String sdp = CameraSdp.prepareRemoteOffer(OFFER, Arrays.asList("h264", "vp8", "vp9"));
        String mline = sdp.split("\r\n")[4];
        assertTrue(mline, mline.startsWith("m=video 9 UDP/TLS/RTP/SAVPF 102"));
        assertTrue(sdp.contains("x-google-max-bitrate=" + CameraSdp.MAX_KBPS));
        assertFalse(sdp.contains("typ srflx"));
    }

    @Test public void readsTheCopiedLinkAndCode() {
        PeerCameraLink.Target t = PeerCameraLink.parse("http://192.168.43.1:43117\nCode: 123456");
        assertNotNull(t);
        assertEquals("http://192.168.43.1:43117", t.origin);
        assertEquals("123456", t.code);
        assertNotNull(PeerCameraLink.parse("code 654321 http://10.0.0.5:43117/"));
        assertNull(PeerCameraLink.parse("http://192.168.43.1:43117"));
        assertNull(PeerCameraLink.parse("http://999.168.43.1:43117 123456"));
        assertNull(PeerCameraLink.parse("http://192.168.43.1:8080 123456"));
        assertNull(PeerCameraLink.parse(null));
    }
}
