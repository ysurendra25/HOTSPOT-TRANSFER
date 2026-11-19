package com.pocketbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenStreamTuningTest {
    private static final String OFFER = String.join("\r\n",
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111",
            "a=rtpmap:111 opus/48000/2",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103",
            "b=AS:5000",
            "b=TIAS:5000000",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000",
            "a=fmtp:97 apt=96",
            "a=rtpmap:102 H264/90000",
            "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:103 rtx/90000",
            "a=fmtp:103 apt=102",
            "");

    @Test public void liftsTheBandwidthCapAndSetsHighBitrates() {
        String tuned = ScreenStreamTuning.tuneOffer(OFFER);
        assertFalse(tuned.contains("b=AS:"));
        assertFalse(tuned.contains("b=TIAS:"));
        String params = "x-google-start-bitrate=8000;x-google-min-bitrate=1500;x-google-max-bitrate=25000";
        assertTrue("VP8 gets its own fmtp line", tuned.contains("a=rtpmap:96 VP8/90000\r\na=fmtp:96 " + params));
        assertTrue("H.264 keeps its profile and gains bitrates",
                tuned.contains("a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f;" + params));
        assertTrue("retransmission streams are left alone", tuned.contains("a=fmtp:97 apt=96\r\n"));
        assertTrue("audio is left alone", tuned.contains("a=fmtp:111 minptime=10;useinbandfec=1\r\n"));
        assertTrue("CRLF line endings are kept", tuned.endsWith("\r\n"));
    }

    @Test public void tuningTwiceDoesNotStackParameters() {
        String twice = ScreenStreamTuning.tuneOffer(ScreenStreamTuning.tuneOffer(OFFER));
        assertEquals(ScreenStreamTuning.tuneOffer(OFFER), twice);
    }

    @Test public void theCeilingSuitsALocalHotspot() {
        assertTrue(ScreenStreamTuning.MAX_KBPS >= 20_000);
        assertTrue(ScreenStreamTuning.START_KBPS > 5_000);
    }
}
