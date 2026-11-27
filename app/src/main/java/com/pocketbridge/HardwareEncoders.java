package com.pocketbridge;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the phone's video encoders from MediaCodecList so the screen stream
 * can pick one that runs in hardware and a size it accepts. Mirrors the rules
 * WebRTC's HardwareVideoEncoderFactory uses to decide what counts as hardware.
 */
final class HardwareEncoders {
    private HardwareEncoders() { }

    static List<ScreenEncoderPlan.Encoder> list() {
        List<ScreenEncoderPlan.Encoder> result = new ArrayList<>();
        MediaCodecInfo[] infos;
        try {
            infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } catch (RuntimeException unavailable) {
            return result;
        }
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                String codec = codecFor(type);
                if (codec == null) continue;
                boolean hardware = isHardware(info, codec);
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    if (video == null) continue;
                    if (!supportsSurfaceOrYuv(caps)) continue;
                    int maxW = video.getSupportedWidths().getUpper();
                    int maxH = video.getSupportedHeights().getUpper();
                    final MediaCodecInfo.VideoCapabilities exact = video;
                    result.add(new ScreenEncoderPlan.Encoder(codec, hardware, maxW, maxH,
                            video.getWidthAlignment(), video.getHeightAlignment(),
                            (w, h) -> {
                                try {
                                    // 30 fps at that size, or the size alone when the
                                    // rate table is missing on older vendor images.
                                    return exact.areSizeAndRateSupported(w, h, 30) || exact.isSizeSupported(w, h);
                                } catch (RuntimeException ignored) {
                                    return w <= maxW && h <= maxH;
                                }
                            }));
                } catch (RuntimeException ignored) { }
            }
        }
        return result;
    }

    private static String codecFor(String mime) {
        switch (mime.toLowerCase(Locale.ROOT)) {
            case "video/avc": return "h264";
            case "video/x-vnd.on2.vp8": return "vp8";
            case "video/x-vnd.on2.vp9": return "vp9";
            default: return null;
        }
    }

    private static boolean supportsSurfaceOrYuv(MediaCodecInfo.CodecCapabilities caps) {
        for (int format : caps.colorFormats) {
            if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    || format == MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar
                    || format == 0x7FA30C04 /* QCOM 32m */) return true;
        }
        return false;
    }

    /**
     * Platform view of "hardware". The caller intersects this list with the
     * codecs org.webrtc.HardwareVideoEncoderFactory reports, which is the
     * authoritative answer to which codecs WebRTC will run in hardware.
     */
    static boolean isHardware(MediaCodecInfo info, String codec) {
        String name = info.getName().toLowerCase(Locale.ROOT);
        if (Build.VERSION.SDK_INT >= 29 && (info.isSoftwareOnly() || !info.isHardwareAccelerated())) return false;
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw."));
    }

    /** Keeps only encoders whose codec WebRTC's hardware factory will actually use. */
    static List<ScreenEncoderPlan.Encoder> usableByWebRtc(List<ScreenEncoderPlan.Encoder> all, java.util.Set<String> webRtcHardwareCodecs) {
        List<ScreenEncoderPlan.Encoder> kept = new ArrayList<>();
        for (ScreenEncoderPlan.Encoder e : all) if (e.hardware && webRtcHardwareCodecs.contains(e.codec)) kept.add(e);
        return kept;
    }
}
