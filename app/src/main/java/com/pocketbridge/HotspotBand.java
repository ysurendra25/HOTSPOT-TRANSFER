package com.pocketbridge;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Best-effort reading of the hotspot's Wi-Fi band, and the Connect hint built from it.
 *
 * Android has no public API that reports the band of the user's own hotspot, and a
 * local-only hotspot's band cannot be chosen by an app. So the band comes from the
 * platform's own config objects where the device still lets an app read them, and
 * the hint falls back to "check the band" when it cannot be read.
 */
final class HotspotBand {
    static final int UNKNOWN = 0;
    static final int GHZ_2_4 = 1;
    static final int GHZ_5 = 2;
    static final int GHZ_6 = 3;

    enum Hint { NONE, SWITCH_TO_5, CHECK_BAND }

    /** Set by ShareSessionService when it starts a local-only hotspot. */
    static volatile int localHotspotBand = UNKNOWN;

    private HotspotBand() { }

    static int fromFrequency(int mhz) {
        if (mhz >= 2400 && mhz < 2500) return GHZ_2_4;
        if (mhz >= 4900 && mhz < 5925) return GHZ_5;
        if (mhz >= 5925 && mhz <= 7125) return GHZ_6;
        return UNKNOWN;
    }

    /** SoftApConfiguration band bits: 1 = 2.4 GHz, 2 = 5 GHz, 4 = 6 GHz, 8 = 60 GHz. */
    static int fromSoftApBandMask(int mask) {
        if (mask == 1) return GHZ_2_4;
        if ((mask & 1) != 0) return UNKNOWN; // 2.4 GHz allowed alongside others: the AP chooses
        if ((mask & 2) != 0) return GHZ_5;
        if ((mask & 4) != 0) return GHZ_6;
        return UNKNOWN;
    }

    /** WifiConfiguration.apBand before Android 11: 0 = 2.4 GHz, 1 = 5 GHz, -1 = any. */
    static int fromLegacyApBand(int apBand) {
        if (apBand == 0) return GHZ_2_4;
        if (apBand == 1) return GHZ_5;
        return UNKNOWN;
    }

    static Hint hint(int band, boolean hotspotMode, boolean supports5Ghz) {
        if (!hotspotMode || !supports5Ghz) return Hint.NONE;
        if (band == GHZ_2_4) return Hint.SWITCH_TO_5;
        if (band == UNKNOWN) return Hint.CHECK_BAND;
        return Hint.NONE;
    }

    static String message(Hint hint) {
        switch (hint) {
            case SWITCH_TO_5:
                return "Your hotspot is on 2.4 GHz, which is slow for videos and big files. "
                        + "Switch the hotspot band to 5 GHz in Android settings, then reconnect the laptop.";
            case CHECK_BAND:
                return "Android does not let PocketBridge read the hotspot band. If transfers feel slow, "
                        + "set the hotspot band to 5 GHz in Android settings, then reconnect the laptop.";
            default:
                return "";
        }
    }

    /** Reads a SoftApConfiguration (local-only hotspot) through its hidden getBand(). */
    static int fromSoftApConfiguration(Object config) {
        if (config == null) return UNKNOWN;
        try {
            Method getBand = config.getClass().getMethod("getBand");
            return fromSoftApBandMask((Integer) getBand.invoke(config));
        } catch (Throwable unavailable) {
            return UNKNOWN;
        }
    }

    /** The band of the user's own hotspot, when this device still exposes its config. */
    static int detectTethered(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return UNKNOWN;
        try {
            Method method = WifiManager.class.getMethod("getSoftApConfiguration");
            int band = fromSoftApConfiguration(method.invoke(wifi));
            if (band != UNKNOWN) return band;
        } catch (Throwable notAllowed) { /* needs a system permission on most phones */ }
        try {
            Method method = WifiManager.class.getMethod("getWifiApConfiguration");
            Object config = method.invoke(wifi);
            if (config != null) {
                Field apBand = config.getClass().getField("apBand");
                return fromLegacyApBand(apBand.getInt(config));
            }
        } catch (Throwable notAllowed) { /* hidden or refused */ }
        return UNKNOWN;
    }

    static boolean supports5Ghz(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try { return wifi != null && wifi.is5GHzBandSupported(); } catch (Throwable unknown) { return true; }
    }

    /** Hotspot settings first, then the Tethering screen, then network settings. */
    static Intent[] settingsIntents() {
        return new Intent[]{
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$WifiApSettingsActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
        };
    }
}
