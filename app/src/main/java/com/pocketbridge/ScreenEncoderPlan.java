package com.pocketbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the codec and capture size for the phone-to-laptop screen stream.
 *
 * Why this exists (0.7.0.1): from 0.5.9.x to 0.6.0.x the capture moved from a
 * 1920-pixel cap to the phone's native size (for example 1080x2400 or
 * 1440x3200), while the codec was left to whatever the browser offered first.
 * Edge offers VP8 first, and most phones (MediaTek, many Exynos/Tensor) have no
 * hardware VP8 encoder that WebRTC will use, so the phone fell back to the
 * software libvpx encoder at native resolution. That encoder cannot keep up:
 * frames queue in the pacer (up to about two seconds), frames drop, and the
 * whole pipeline freezes when a keyframe is needed. Hardware encoders also have
 * a maximum size; a portrait 1080x2400 frame is too tall for encoders that only
 * accept 1920x1088, which is what produced corrupted or half pictures.
 *
 * The plan therefore prefers a hardware H.264 encoder (no B-frames in WebRTC's
 * constrained-baseline profile, short keyframe interval set by WebRTC), then
 * hardware VP8, and only then software VP8 at a reduced size. The capture is
 * kept at the native size whenever the chosen encoder accepts it, and otherwise
 * scaled to the largest size it does accept.
 *
 * Plain Java so it is unit tested without a device.
 */
final class ScreenEncoderPlan {
    /** What one encoder on this phone can do. */
    static final class Encoder {
        final String codec;      // "h264", "vp8", "vp9"
        final boolean hardware;
        final int maxWidth;
        final int maxHeight;
        final int widthAlignment;
        final int heightAlignment;
        /** Optional exact check from MediaCodecInfo.VideoCapabilities; null means "use the bounds". */
        final SizeCheck sizeCheck;

        Encoder(String codec, boolean hardware, int maxWidth, int maxHeight, int widthAlignment, int heightAlignment, SizeCheck sizeCheck) {
            this.codec = codec;
            this.hardware = hardware;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.widthAlignment = Math.max(2, widthAlignment);
            this.heightAlignment = Math.max(2, heightAlignment);
            this.sizeCheck = sizeCheck;
        }

        boolean accepts(int width, int height) {
            if (sizeCheck != null) return sizeCheck.supported(width, height);
            return width <= maxWidth && height <= maxHeight;
        }
    }

    interface SizeCheck { boolean supported(int width, int height); }

    /** Longest edge used when only a software encoder is available. */
    static final int SOFTWARE_MAX_EDGE = 1280;

    final String codec;
    final boolean hardware;
    final List<String> codecOrder;
    private final Encoder encoder;

    private ScreenEncoderPlan(String codec, boolean hardware, List<String> codecOrder, Encoder encoder) {
        this.codec = codec;
        this.hardware = hardware;
        this.codecOrder = codecOrder;
        this.encoder = encoder;
    }

    static ScreenEncoderPlan choose(List<Encoder> encoders) {
        Encoder h264 = find(encoders, "h264");
        Encoder vp8 = find(encoders, "vp8");
        Encoder vp9 = find(encoders, "vp9");
        List<String> order = new ArrayList<>();
        Encoder chosen = null;
        if (h264 != null) { order.add("h264"); chosen = h264; }
        if (vp8 != null) { order.add("vp8"); if (chosen == null) chosen = vp8; }
        if (vp9 != null) { order.add("vp9"); if (chosen == null) chosen = vp9; }
        // Software fallbacks: libvpx VP8 is always built into WebRTC. H.264 has no
        // software encoder in the Android SDK, so it is never offered without hardware.
        if (!order.contains("vp8")) order.add("vp8");
        if (!order.contains("vp9")) order.add("vp9");
        String codec = chosen == null ? "vp8" : chosen.codec;
        return new ScreenEncoderPlan(codec, chosen != null, order, chosen);
    }

    private static Encoder find(List<Encoder> encoders, String codec) {
        if (encoders == null) return null;
        Encoder best = null;
        for (Encoder e : encoders) {
            if (e == null || !e.hardware || !codec.equals(e.codec)) continue;
            if (best == null || (long) e.maxWidth * e.maxHeight > (long) best.maxWidth * best.maxHeight) best = e;
        }
        return best;
    }

    /**
     * The capture size for a display of {@code width x height}: the native size
     * when the encoder takes it, otherwise the largest size with the same
     * aspect ratio that it does take. Always even, and aligned the way the
     * encoder asks.
     */
    int[] captureSize(int width, int height) {
        width = Math.max(2, width);
        height = Math.max(2, height);
        if (encoder == null) {
            int[] fitted = ScreenCaptureSizing.fitWithinMaxEdge(width, height, SOFTWARE_MAX_EDGE);
            return ScreenCaptureSizing.evenSize(fitted[0], fitted[1]);
        }
        int[] native_ = aligned(width, height);
        if (encoder.accepts(native_[0], native_[1])) return native_;
        // Shrink step by step, keeping the aspect ratio, until the encoder accepts it.
        double scale = 1.0;
        if (encoder.maxWidth > 0 && encoder.maxHeight > 0) {
            scale = Math.min(1.0, Math.min((double) encoder.maxWidth / width, (double) encoder.maxHeight / height));
        }
        for (int i = 0; i < 64; i++) {
            int[] candidate = aligned((int) Math.floor(width * scale), (int) Math.floor(height * scale));
            if (encoder.accepts(candidate[0], candidate[1])) return candidate;
            scale *= 0.95;
        }
        int[] fitted = ScreenCaptureSizing.fitWithinMaxEdge(width, height, 1280);
        return aligned(fitted[0], fitted[1]);
    }

    private int[] aligned(int width, int height) {
        int wa = encoder == null ? 2 : encoder.widthAlignment;
        int ha = encoder == null ? 2 : encoder.heightAlignment;
        int w = Math.max(wa, (width / wa) * wa);
        int h = Math.max(ha, (height / ha) * ha);
        return ScreenCaptureSizing.evenSize(w, h);
    }

    String describe() {
        return codec.toUpperCase(Locale.ROOT) + (hardware ? " hardware" : " software");
    }

    /**
     * Reorders every video m-line of an SDP offer so the payload types of the
     * preferred codecs come first. libwebrtc keeps the offer's order when it
     * answers, so the answer (and therefore the phone's send codec) follows it.
     * Among H.264 entries, packetization-mode=1 constrained baseline (42e0xx /
     * 4200xx) is preferred: every hardware encoder supports it and it has no
     * B-frames. RTX payloads move with the codec they repair.
     */
    static String preferCodecs(String sdp, List<String> codecOrder) {
        if (sdp == null || sdp.isEmpty() || codecOrder == null || codecOrder.isEmpty()) return sdp;
        String newline = sdp.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = sdp.split("\r?\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.startsWith("m=video")) { out.add(line); i++; continue; }
            List<String> section = new ArrayList<>();
            section.add(line);
            i++;
            while (i < lines.length && !lines[i].startsWith("m=")) { section.add(lines[i]); i++; }
            Map<String, String> codecs = new HashMap<>();
            Map<String, String> fmtp = new HashMap<>();
            for (String s : section) {
                if (s.startsWith("a=rtpmap:")) {
                    String rest = s.substring(9);
                    int space = rest.indexOf(' ');
                    if (space > 0) {
                        String name = rest.substring(space + 1);
                        int slash = name.indexOf('/');
                        codecs.put(rest.substring(0, space), (slash > 0 ? name.substring(0, slash) : name).toLowerCase(Locale.ROOT));
                    }
                } else if (s.startsWith("a=fmtp:")) {
                    String rest = s.substring(7);
                    int space = rest.indexOf(' ');
                    if (space > 0) fmtp.put(rest.substring(0, space), rest.substring(space + 1).toLowerCase(Locale.ROOT));
                }
            }
            String[] mParts = section.get(0).split(" ");
            if (mParts.length <= 3) { out.addAll(section); continue; }
            List<String> payloads = new ArrayList<>();
            for (int p = 3; p < mParts.length; p++) payloads.add(mParts[p]);
            // RTX payload -> the payload it repairs.
            Map<String, String> rtxFor = new HashMap<>();
            for (String pt : payloads) {
                if ("rtx".equals(codecs.get(pt))) {
                    String f = fmtp.get(pt);
                    if (f != null) {
                        int at = f.indexOf("apt=");
                        if (at >= 0) {
                            String apt = f.substring(at + 4).split("[;\\s]")[0];
                            rtxFor.put(pt, apt);
                        }
                    }
                }
            }
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (String wanted : codecOrder) {
                List<String> matches = new ArrayList<>();
                for (String pt : payloads) if (wanted.equals(codecs.get(pt))) matches.add(pt);
                if ("h264".equals(wanted)) matches.sort((a, b) -> Integer.compare(h264Rank(fmtp.get(a)), h264Rank(fmtp.get(b))));
                for (String pt : matches) {
                    ordered.add(pt);
                    for (Map.Entry<String, String> rtx : rtxFor.entrySet()) if (rtx.getValue().equals(pt)) ordered.add(rtx.getKey());
                }
            }
            for (String pt : payloads) ordered.add(pt); // everything else keeps its place after
            StringBuilder m = new StringBuilder(mParts[0]).append(' ').append(mParts[1]).append(' ').append(mParts[2]);
            for (String pt : ordered) m.append(' ').append(pt);
            section.set(0, m.toString());
            out.addAll(section);
        }
        return String.join(newline, out);
    }

    /** Lower is better. */
    static int h264Rank(String fmtp) {
        String f = fmtp == null ? "" : fmtp;
        boolean mode1 = f.contains("packetization-mode=1");
        String profile = "";
        int at = f.indexOf("profile-level-id=");
        if (at >= 0) profile = f.substring(at + 17).split("[;\\s]")[0];
        boolean baseline = profile.startsWith("42e0") || profile.startsWith("4200");
        if (mode1 && baseline) return 0;
        if (mode1 && profile.startsWith("4d")) return 1;   // main: no B-frames from WebRTC encoders
        if (mode1) return 2;                               // constrained high
        return 3;                                          // packetization-mode=0 last
    }

    /** Codec names present on the video m-lines of an SDP, for diagnostics and tests. */
    static Set<String> firstVideoCodec(String sdp) {
        Set<String> result = new LinkedHashSet<>();
        if (sdp == null) return result;
        String[] lines = sdp.split("\r?\n");
        String first = null;
        Map<String, String> codecs = new HashMap<>();
        for (String s : lines) {
            if (s.startsWith("m=video") && first == null) {
                String[] parts = s.split(" ");
                if (parts.length > 3) first = parts[3];
            } else if (s.startsWith("a=rtpmap:")) {
                String rest = s.substring(9);
                int space = rest.indexOf(' ');
                if (space > 0) {
                    String name = rest.substring(space + 1);
                    int slash = name.indexOf('/');
                    codecs.putIfAbsent(rest.substring(0, space), (slash > 0 ? name.substring(0, slash) : name).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (first != null && codecs.containsKey(first)) result.add(codecs.get(first));
        return result;
    }
}
