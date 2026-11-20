package com.pocketbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bitrate settings for the phone-to-laptop screen stream.
 *
 * A phone hotspot carries far more than the 5–6 Mbps the stream used to be
 * capped at, and a capped encoder at native resolution has to blur text or
 * drop frames. The phone is the sender, and libwebrtc takes the sender's
 * start/min/max bitrate from the codec parameters of the description it
 * receives, so the browser's offer is tuned here before the phone applies it.
 * Plain Java so it is unit tested.
 */
final class ScreenStreamTuning {
    /** A practical hotspot ceiling; WebRTC congestion control may go lower as conditions change. */
    static final int MAX_KBPS = 12_000;
    /** Start sharp, while leaving headroom for the link's congestion controller. */
    static final int START_KBPS = 5_000;
    /** Low enough to recover quickly when the hotspot is briefly congested. */
    static final int MIN_KBPS = 500;
    static final int MAX_FRAMERATE = 30;

    private static final Set<String> VIDEO_CODECS = new HashSet<>();
    static {
        VIDEO_CODECS.add("vp8");
        VIDEO_CODECS.add("vp9");
        VIDEO_CODECS.add("h264");
        VIDEO_CODECS.add("h265");
        VIDEO_CODECS.add("av1");
    }

    private ScreenStreamTuning() { }

    static String tuneOffer(String sdp) {
        return tune(sdp, START_KBPS, MIN_KBPS, MAX_KBPS);
    }

    /**
     * Removes bandwidth caps (b=AS / b=TIAS) from video sections and adds
     * x-google start/min/max bitrates to every real video codec.
     */
    static String tune(String sdp, int startKbps, int minKbps, int maxKbps) {
        if (sdp == null || sdp.isEmpty()) return sdp;
        String newline = sdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = sdp.split("\r?\n", -1);
        List<String> out = new ArrayList<>();
        String params = "x-google-start-bitrate=" + startKbps
                + ";x-google-min-bitrate=" + minKbps
                + ";x-google-max-bitrate=" + maxKbps;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.startsWith("m=video")) {
                out.add(line);
                i++;
                continue;
            }
            // Collect the whole video section.
            List<String> section = new ArrayList<>();
            section.add(line);
            i++;
            while (i < lines.length && !lines[i].startsWith("m=")) {
                section.add(lines[i]);
                i++;
            }
            Map<String, String> codecByPayload = new HashMap<>();
            Set<String> payloadsWithFmtp = new HashSet<>();
            for (String s : section) {
                if (s.startsWith("a=rtpmap:")) {
                    String rest = s.substring("a=rtpmap:".length());
                    int space = rest.indexOf(' ');
                    if (space > 0) {
                        String pt = rest.substring(0, space);
                        String name = rest.substring(space + 1);
                        int slash = name.indexOf('/');
                        codecByPayload.put(pt, (slash > 0 ? name.substring(0, slash) : name).toLowerCase(Locale.ROOT));
                    }
                } else if (s.startsWith("a=fmtp:")) {
                    String rest = s.substring("a=fmtp:".length());
                    int space = rest.indexOf(' ');
                    if (space > 0) payloadsWithFmtp.add(rest.substring(0, space));
                }
            }
            List<String> tuned = new ArrayList<>();
            List<String> missingFmtp = new ArrayList<>();
            for (Map.Entry<String, String> codec : codecByPayload.entrySet()) {
                if (VIDEO_CODECS.contains(codec.getValue()) && !payloadsWithFmtp.contains(codec.getKey())) missingFmtp.add(codec.getKey());
            }
            for (String s : section) {
                if (s.startsWith("b=AS:") || s.startsWith("b=TIAS:")) continue;
                if (s.startsWith("a=fmtp:")) {
                    String rest = s.substring("a=fmtp:".length());
                    int space = rest.indexOf(' ');
                    String pt = space > 0 ? rest.substring(0, space) : rest;
                    String codec = codecByPayload.get(pt);
                    if (codec != null && VIDEO_CODECS.contains(codec) && !s.contains("x-google-max-bitrate")) {
                        s = s + (s.endsWith(" ") ? "" : ";") + params;
                    }
                }
                tuned.add(s);
                // A codec without an fmtp line (VP8 usually) gets one right after its rtpmap.
                if (s.startsWith("a=rtpmap:")) {
                    String rest = s.substring("a=rtpmap:".length());
                    int space = rest.indexOf(' ');
                    String pt = space > 0 ? rest.substring(0, space) : "";
                    if (missingFmtp.contains(pt)) tuned.add("a=fmtp:" + pt + " " + params);
                }
            }
            out.addAll(tuned);
        }
        return String.join(newline, out);
    }
}
