package com.pocketbridge;

import java.util.ArrayList;
import java.util.List;

/** SDP adjustments for the camera call on a hotspot with no STUN or TURN server. */
final class CameraSdp {
    static final int START_KBPS = 1500;
    static final int MIN_KBPS = 300;
    static final int MAX_KBPS = 3000;

    private CameraSdp() { }

    /**
     * Keeps only UDP host candidates. Without STUN there are no reflexive
     * candidates to wait for, and TCP or relay candidates only slow ICE down on
     * the local link. Browser mDNS (.local) host candidates stay: the phone
     * still learns the real address as a peer-reflexive candidate when the
     * laptop's checks arrive.
     */
    static String hostCandidatesOnly(String sdp) {
        if (sdp == null || sdp.isEmpty()) return sdp;
        String newline = sdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = sdp.split("\r?\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.startsWith("a=candidate:") && !isUdpHost(line)) continue;
            out.add(line);
        }
        return String.join(newline, out);
    }

    static boolean isUdpHost(String candidateLine) {
        String[] parts = candidateLine.trim().split("\\s+");
        // a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> ...
        if (parts.length < 8) return false;
        boolean udp = "udp".equalsIgnoreCase(parts[2]);
        boolean host = "typ".equals(parts[6]) && "host".equals(parts[7]);
        return udp && host;
    }

    /** Hardware codec first (as the screen path does), then camera bitrates, then host candidates only. */
    static String prepareRemoteOffer(String sdp, List<String> codecOrder) {
        String preferred = ScreenEncoderPlan.preferCodecs(sdp, codecOrder);
        return hostCandidatesOnly(ScreenStreamTuning.tune(preferred, START_KBPS, MIN_KBPS, MAX_KBPS));
    }

    /** A phone calling another phone puts its hardware codec first in its own offer. */
    static String prepareLocalOffer(String sdp, List<String> codecOrder) {
        return hostCandidatesOnly(ScreenEncoderPlan.preferCodecs(sdp, codecOrder));
    }
}
