package com.pocketbridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScreenEncoderPlanTest {
    private static ScreenEncoderPlan.Encoder hw(String codec, int maxW, int maxH) {
        // Encoders accept either orientation up to the bounds, like most vendor tables.
        return new ScreenEncoderPlan.Encoder(codec, true, Math.max(maxW, maxH), Math.max(maxW, maxH), 2, 2,
                (w, h) -> Math.max(w, h) <= Math.max(maxW, maxH) && Math.min(w, h) <= Math.min(maxW, maxH));
    }

    // An Edge offer lists VP8 first; H.264 entries come later with rtx after each.
    private static final String EDGE_OFFER = String.join("\r\n",
            "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 106 107 39 40",
            "c=IN IP4 0.0.0.0",
            "a=rtpmap:96 VP8/90000",
            "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96",
            "a=rtpmap:102 H264/90000", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
            "a=rtpmap:103 rtx/90000", "a=fmtp:103 apt=102",
            "a=rtpmap:106 H264/90000", "a=fmtp:106 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
            "a=rtpmap:107 rtx/90000", "a=fmtp:107 apt=106",
            "a=rtpmap:39 AV1/90000",
            "a=rtpmap:40 rtx/90000", "a=fmtp:40 apt=39",
            "");

    @Test public void prefersHardwareH264AndPutsConstrainedBaselineFirst() {
        ScreenEncoderPlan plan = ScreenEncoderPlan.choose(Arrays.asList(hw("h264", 4096, 2304), hw("vp8", 1920, 1088)));
        assertEquals("h264", plan.codec);
        assertTrue(plan.hardware);
        String tuned = ScreenEncoderPlan.preferCodecs(EDGE_OFFER, plan.codecOrder);
        assertTrue(tuned, tuned.contains("m=video 9 UDP/TLS/RTP/SAVPF 106 107 102 103 96 97 39 40\r\n"));
        assertEquals(Collections.singleton("h264"), ScreenEncoderPlan.firstVideoCodec(tuned));
        // Nothing else in the offer changes.
        assertEquals(EDGE_OFFER.split("\r\n").length, tuned.split("\r\n").length);
    }

    @Test public void fallsBackToHardwareVp8ThenSoftwareVp8() {
        ScreenEncoderPlan vp8 = ScreenEncoderPlan.choose(Collections.singletonList(hw("vp8", 1920, 1088)));
        assertEquals("vp8", vp8.codec);
        assertTrue(vp8.hardware);
        ScreenEncoderPlan none = ScreenEncoderPlan.choose(Collections.emptyList());
        assertEquals("vp8", none.codec);
        assertFalse(none.hardware);
        assertFalse("H.264 has no software encoder on Android", none.codecOrder.contains("h264"));
        int[] size = none.captureSize(1080, 2400);
        assertTrue(Math.max(size[0], size[1]) <= ScreenEncoderPlan.SOFTWARE_MAX_EDGE);
    }

    @Test public void keepsNativeSizeWhenTheEncoderAcceptsIt() {
        ScreenEncoderPlan plan = ScreenEncoderPlan.choose(Collections.singletonList(hw("h264", 4096, 2304)));
        int[] size = plan.captureSize(1080, 2400);
        assertEquals(1080, size[0]);
        assertEquals(2400, size[1]);
    }

    @Test public void scalesATallPortraitScreenIntoA1080pEncoder() {
        // 1080x2400 does not fit an encoder limited to 1920x1088 in either orientation.
        ScreenEncoderPlan plan = ScreenEncoderPlan.choose(Collections.singletonList(hw("h264", 1920, 1088)));
        int[] size = plan.captureSize(1080, 2400);
        assertTrue(Arrays.toString(size), Math.max(size[0], size[1]) <= 1920 && Math.min(size[0], size[1]) <= 1088);
        assertEquals(0, size[0] % 2);
        assertEquals(0, size[1] % 2);
        double native_ = 1080.0 / 2400.0, scaled = (double) size[0] / size[1];
        assertTrue("aspect ratio kept: " + scaled, Math.abs(native_ - scaled) < 0.01);
        assertTrue("not shrunk more than needed", size[1] >= 1800);
    }

    @Test public void honoursEncoderAlignment() {
        ScreenEncoderPlan.Encoder aligned16 = new ScreenEncoderPlan.Encoder("h264", true, 4096, 4096, 16, 16, null);
        int[] size = ScreenEncoderPlan.choose(Collections.singletonList(aligned16)).captureSize(1080, 2340);
        assertEquals(0, size[0] % 16);
        assertEquals(0, size[1] % 16);
    }

    @Test public void ranksH264Profiles() {
        assertTrue(ScreenEncoderPlan.h264Rank("packetization-mode=1;profile-level-id=42e01f")
                < ScreenEncoderPlan.h264Rank("packetization-mode=1;profile-level-id=640c1f"));
        assertTrue(ScreenEncoderPlan.h264Rank("packetization-mode=1;profile-level-id=640c1f")
                < ScreenEncoderPlan.h264Rank("packetization-mode=0;profile-level-id=42e01f"));
    }

    @Test public void leavesAnOfferWithoutVideoAlone() {
        String audioOnly = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n";
        List<String> order = Arrays.asList("h264", "vp8");
        assertEquals(audioOnly, ScreenEncoderPlan.preferCodecs(audioOnly, order));
    }
}
