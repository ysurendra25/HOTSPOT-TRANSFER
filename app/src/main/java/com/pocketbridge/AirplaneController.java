package com.pocketbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * The PHONE's airplane mode reset: airplane ON, airplane OFF, hotspot back ON,
 * to give the phone a fresh mobile-data connection.
 *
 * A normal Android app cannot switch airplane mode or start the Wi-Fi hotspot.
 * This class uses the best route that is actually available, in order:
 *
 *  1. "secure-settings": the user granted WRITE_SECURE_SETTINGS once over adb
 *     ({@code adb shell pm grant com.pocketbridge android.permission.WRITE_SECURE_SETTINGS}).
 *     The app writes Settings.Global.AIRPLANE_MODE_ON and checks that the
 *     mobile network really went down. On many Android versions the radios
 *     only follow the system broadcast, which apps may not send; when the
 *     radios do not follow, the setting is put back and the next route is used.
 *  2. "accessibility": the PocketBridge accessibility service is enabled. It
 *     opens Quick Settings and taps the Airplane mode and Hotspot tiles, the
 *     way the user would. Works when those tiles are on the first Quick
 *     Settings page and their labels are recognisable.
 *  3. "guided": a high-priority notification opens a full-screen step-by-step
 *     screen that opens the airplane mode settings, then the hotspot settings,
 *     and confirms when the hotspot is back.
 *
 * The hotspot itself has no public API at all, so after the airplane step the
 * hotspot is restored by the accessibility service or by the guided screen.
 */
final class AirplaneController {
    static final String CHANNEL_ID = "pocketbridge_airplane";
    static final int NOTIFICATION_ID = 1407;
    static final String ADB_GRANT = "adb shell pm grant com.pocketbridge android.permission.WRITE_SECURE_SETTINGS";

    private static final Object LOCK = new Object();
    private static volatile boolean cycling;
    private static volatile String step = "idle";
    private static volatile String lastResult = "";
    private static volatile long cycleStartedAt;

    private AirplaneController() { }

    // ---------------------------------------------------------------- state

    static boolean isAirplaneOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    static boolean hasSecureSettings(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static String method(Context context) {
        if (hasSecureSettings(context)) return "secure-settings";
        if (AirplaneAccessibilityService.isConnected()) return "accessibility";
        return "guided";
    }

    /** True while the phone's Wi-Fi hotspot is up. */
    static boolean isHotspotOn(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Object enabled = WifiManager.class.getMethod("isWifiApEnabled").invoke(wifi);
            if (enabled instanceof Boolean) return (Boolean) enabled;
        } catch (Throwable hiddenApiUnavailable) { /* fall through to the interface scan */ }
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.ROOT);
                if (!ShareSessionService.isAccessPointInterface(name)) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** True when the phone has a cellular data network right now. */
    static boolean hasCellularNetwork(Context context) {
        try {
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static JSONObject status(Context context) {
        JSONObject state = new JSONObject();
        try {
            String method = method(context);
            state.put("ok", true)
                    .put("side", "phone")
                    .put("on", isAirplaneOn(context))
                    .put("hotspot", isHotspotOn(context))
                    .put("method", method)
                    .put("automatic", !"guided".equals(method))
                    .put("secureSettingsGranted", hasSecureSettings(context))
                    .put("accessibilityEnabled", AirplaneAccessibilityService.isConnected())
                    .put("cycling", cycling)
                    .put("step", step)
                    .put("lastResult", lastResult)
                    .put("adbCommand", ADB_GRANT)
                    .put("laptopRelay", LaptopRelay.INSTANCE.helperConnected());
        } catch (Exception ignored) { }
        return state;
    }

    // ---------------------------------------------------------------- HTTP API

    /**
     * GET /api/airplane → status. POST {"action": "cycle" | "on" | "off" | "guide"}.
     * Answers at once; the reset runs in the background because the hotspot
     * (and with it this connection) goes away during it.
     */
    static JSONObject handleApi(Context context, String httpMethod, JSONObject request) {
        try {
            if (!"POST".equals(httpMethod)) return status(context);
            String action = request.optString("action", "status");
            switch (action) {
                case "cycle":
                    if (!startCycle(context, request.optString("from", "laptop"))) {
                        return status(context).put("ok", false).put("httpStatus", 409).put("error", "A reset is already running on the phone.");
                    }
                    return status(context).put("started", true).put("message", messageForStart(context));
                case "on":
                case "off": {
                    boolean on = "on".equals(action);
                    if (hasSecureSettings(context) && setAirplaneVerified(context, on)) {
                        return status(context).put("result", "switched " + action + " automatically");
                    }
                    if (on == isAirplaneOn(context)) return status(context).put("result", "already " + action);
                    showGuide(context, on ? "Turn airplane mode ON" : "Turn airplane mode OFF", "manual");
                    return status(context).put("manual", true).put("result", "shown on the phone");
                }
                case "guide":
                    showGuide(context, "Refresh mobile data", "start");
                    return status(context).put("manual", true);
                default:
                    return status(context);
            }
        } catch (Exception error) {
            JSONObject failure = new JSONObject();
            try { failure.put("ok", false).put("httpStatus", 500).put("error", String.valueOf(error.getMessage())); } catch (Exception ignored) { }
            return failure;
        }
    }

    private static String messageForStart(Context context) {
        switch (method(context)) {
            case "secure-settings": return "The phone is switching airplane mode on and off. The hotspot and this page drop for a moment.";
            case "accessibility": return "The phone is tapping its Airplane mode and Hotspot tiles. The hotspot and this page drop for a moment.";
            default: return "Finish the reset on the phone: a notification there walks through airplane mode and the hotspot.";
        }
    }

    // ---------------------------------------------------------------- the reset

    /** Starts one reset; false when one is already running. */
    static boolean startCycle(Context context, String source) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (cycling && System.currentTimeMillis() - cycleStartedAt < 3 * 60_000) return false;
            cycling = true;
            cycleStartedAt = System.currentTimeMillis();
            step = "starting";
            lastResult = "";
        }
        // Ask a connected laptop to keep rejoining the hotspot while it is away.
        if (LaptopRelay.INSTANCE.helperConnected()) {
            try {
                LaptopRelay.INSTANCE.enqueue("hotspot-reconnect", false, new JSONObject().put("minutes", 10));
            } catch (Exception ignored) { }
        }
        Thread worker = new Thread(() -> runCycle(app), "PocketBridge-airplane");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void runCycle(Context context) {
        try {
            // Give the laptop's long-poll a moment to collect its "rejoin" command.
            sleep(LaptopRelay.INSTANCE.helperConnected() ? 1500 : 200);
            boolean airplaneDone = false;
            if (hasSecureSettings(context)) {
                step = "airplane-on";
                if (setAirplaneVerified(context, true)) {
                    sleep(3000);
                    step = "airplane-off";
                    airplaneDone = setAirplaneVerified(context, false);
                    if (!airplaneDone) setAirplaneSetting(context, false);
                }
            }
            if (!airplaneDone && AirplaneAccessibilityService.isConnected()) {
                step = "airplane-on";
                if (AirplaneAccessibilityService.toggleTile(AirplaneAccessibilityService.TILE_AIRPLANE, true, () -> isAirplaneOn(context))) {
                    sleep(3000);
                    step = "airplane-off";
                    airplaneDone = AirplaneAccessibilityService.toggleTile(AirplaneAccessibilityService.TILE_AIRPLANE, false, () -> isAirplaneOn(context));
                }
            }
            if (!airplaneDone) {
                step = "guided";
                lastResult = "Waiting for the steps on the phone.";
                showGuide(context, "Refresh mobile data", "start");
                return; // the guided screen finishes the cycle
            }
            step = "hotspot";
            if (restoreHotspot(context)) {
                lastResult = "Airplane mode switched on and off; the hotspot is back.";
                finishCycle("done");
            } else {
                lastResult = "Airplane mode switched on and off. Turn the hotspot back on on the phone.";
                showGuide(context, "Turn the hotspot back on", "hotspot");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finishCycle("interrupted");
        } catch (RuntimeException error) {
            lastResult = "Reset failed: " + error.getMessage();
            finishCycle("failed");
        }
    }

    static void finishCycle(String finalStep) {
        synchronized (LOCK) {
            cycling = false;
            step = finalStep;
        }
    }

    static void noteGuidedResult(String result) { lastResult = result; }

    /** Some phones bring the hotspot back by themselves; otherwise tap its tile. */
    private static boolean restoreHotspot(Context context) throws InterruptedException {
        if (waitFor(() -> isHotspotOn(context), 6000)) return true;
        if (AirplaneAccessibilityService.isConnected()) {
            AirplaneAccessibilityService.toggleTile(AirplaneAccessibilityService.TILE_HOTSPOT, true, () -> isHotspotOn(context));
            return waitFor(() -> isHotspotOn(context), 12000);
        }
        return false;
    }

    /** Writes the setting and confirms the mobile radio followed; reverts it when it did not. */
    static boolean setAirplaneVerified(Context context, boolean on) {
        boolean hadCellular = hasCellularNetwork(context);
        if (!setAirplaneSetting(context, on)) return false;
        tryPlatformToggle(context, on);
        try {
            boolean followed = waitFor(() -> on ? !hasCellularNetwork(context) : true, on ? 6000 : 500);
            // Turning ON is verified by the mobile network disappearing. Without a
            // mobile network to begin with there is nothing to observe: trust the setting.
            if (on && hadCellular && !followed) {
                setAirplaneSetting(context, false);
                lastResult = "This Android version ignores the airplane setting from apps (the radios stayed on).";
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    private static boolean setAirplaneSetting(Context context, boolean on) {
        try {
            return Settings.Global.putInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, on ? 1 : 0);
        } catch (SecurityException notGranted) {
            return false;
        }
    }

    /**
     * ConnectivityManager.setAirplaneMode is a system API. Some builds let an
     * app holding WRITE_SECURE_SETTINGS call it; most refuse. Either way the
     * outcome is checked by {@link #setAirplaneVerified}.
     */
    private static void tryPlatformToggle(Context context, boolean on) {
        try {
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            ConnectivityManager.class.getMethod("setAirplaneMode", boolean.class).invoke(connectivity, on);
        } catch (Throwable refused) { /* expected on most phones */ }
    }

    interface Check { boolean ok(); }

    static boolean waitFor(Check check, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return true;
            Thread.sleep(250);
        }
        return check.ok();
    }

    private static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }

    // ---------------------------------------------------------------- guided flow

    /** A high-priority notification whose full-screen intent opens the step-by-step screen. */
    static void showGuide(Context context, String title, String firstStep) {
        Context app = context.getApplicationContext();
        NotificationManager notifications = app.getSystemService(NotificationManager.class);
        if (notifications == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Airplane mode reset", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Asks you to switch airplane mode and the hotspot when the laptop requests a mobile-data reset.");
            notifications.createNotificationChannel(channel);
        }
        Intent open = new Intent(app, AirplaneCycleActivity.class)
                .putExtra(AirplaneCycleActivity.EXTRA_STEP, firstStep)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(app, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL_ID) : new Notification.Builder(app);
        builder.setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle("PocketBridge: " + title)
                .setContentText("Tap to switch airplane mode on and off, then turn the hotspot back on.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true);
        try { notifications.notify(NOTIFICATION_ID, builder.build()); } catch (SecurityException noNotificationPermission) { /* the app UI still offers the flow */ }
    }

    static void clearGuide(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
    }

    /** Opens the tethering screen, trying the known entry points across vendors. */
    static boolean openHotspotSettings(Context context) {
        Intent[] candidates = {
                new Intent("android.settings.TETHER_SETTINGS"),
                new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$WifiTetherSettingsActivity"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
        };
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return true;
            } catch (Exception notOnThisPhone) { /* try the next one */ }
        }
        return false;
    }

    static boolean openAirplaneSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception missing) {
            try {
                context.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception alsoMissing) {
                return false;
            }
        }
    }
}
