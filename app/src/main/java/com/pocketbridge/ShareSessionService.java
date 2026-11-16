package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public final class ShareSessionService extends Service {
    public static final String ACTION_START = "com.pocketbridge.START";
    public static final String ACTION_START_WIFI = "com.pocketbridge.START_WIFI";
    public static final String ACTION_START_HOTSPOT = "com.pocketbridge.START_HOTSPOT";
    public static final String ACTION_STOP = "com.pocketbridge.STOP";
    public static final String ACTION_QUERY = "com.pocketbridge.QUERY";
    public static final String ACTION_STATUS = "com.pocketbridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_MODE = "mode";
    private static final String CHANNEL_ID = "pocketbridge_share";
    private static final int NOTIFICATION_ID = 1201;
    private static final String MODE_WIFI = "Wi-Fi network";
    private static final String MODE_HOTSPOT = "Phone hotspot";
    private static final String WIFI_SSID_HINT = "Join the same Wi-Fi as this phone";
    private static final String WIFI_PASSWORD_HINT = "That network's usual password";
    private static final String HOTSPOT_SSID_HINT = "Use the hotspot already on this phone";
    private static final String HOTSPOT_PASSWORD_HINT = "Keep the laptop on that same Wi-Fi";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private MediaHttpServer server;
    private String currentSsid;
    private String currentPassword;
    private String currentUrl;
    private String currentCode;
    private String currentMode;
    private String currentToken;
    private boolean starting;
    /** True when we serve without owning a local-only hotspot. */
    private boolean usingFallback;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private boolean sessionActive() {
        return reservation != null || usingFallback;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            String status = sessionActive()
                    ? "Sharing over " + (currentMode == null ? "the local network" : currentMode.toLowerCase(Locale.ROOT))
                    : starting ? "Starting…" : "No share is active. Pick a mode to begin.";
            publish(status, currentSsid, currentPassword, currentUrl, currentCode, currentMode);
            if (!sessionActive() && !starting) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_WIFI.equals(action)) {
            if (!sessionActive() && !starting) startWifiSession();
            return START_STICKY;
        }
        if (ACTION_START_HOTSPOT.equals(action) || ACTION_START.equals(action)) {
            if (!sessionActive() && !starting) startHotspotSession();
            return START_STICKY;
        }
        return START_STICKY;
    }

    /**
     * Mode 1: both devices are already on the same Wi-Fi network. No hotspot is
     * created, so this works even on phones that refuse to start one.
     */
    private void startWifiSession() {
        starting = true;
        startForegroundCompat("Starting Wi-Fi sharing…");
        publish("Looking for this phone's Wi-Fi address…", null, null, null, null, MODE_WIFI);
        String address = findWifiClientAddress();
        if (address == null) {
            starting = false;
            publish("This phone is not on a Wi-Fi network. Connect the phone and the laptop to the same Wi-Fi, or use Hotspot mode.", null, null, null, null, MODE_WIFI);
            stopForegroundCompat();
            stopSelf();
            return;
        }
        starting = false;
        beginServer(address, MODE_WIFI, WIFI_SSID_HINT, WIFI_PASSWORD_HINT,
                "Ready. Keep the laptop on the same Wi-Fi as this phone, then open the link and enter the code.");
    }

    /**
     * Mode 2: create a private local-only hotspot. If Android refuses because the
     * phone's own hotspot is already running, share over that one instead.
     */
    private void startHotspotSession() {
        starting = true;
        startForegroundCompat("Starting private hotspot…");
        publish("Starting a local-only hotspot…", null, null, null, null, MODE_HOTSPOT);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    reservation = value;
                    starting = false;
                    WifiConfiguration config = value.getWifiConfiguration();
                    String ssid = config == null ? "PocketBridge" : config.SSID;
                    String password = config == null ? "See Android Wi-Fi settings" : config.preSharedKey;
                    publish("Hotspot ready. Looking up the browser link…", ssid, password, null, null, MODE_HOTSPOT);
                    findAddressAndStartServer(0);
                }

                @Override public void onStopped() {
                    closeServer();
                    reservation = null;
                    starting = false;
                    currentUrl = null;
                    currentCode = null;
                    publish("The hotspot stopped. Start a new share when ready.", null, null, null, null, null);
                    stopSelf();
                }

                @Override public void onFailed(int reason) {
                    starting = false;
                    // Any failure means Android will not give us a hotspot. Try the
                    // hotspot the user already has, then plain Wi-Fi, before giving up.
                    if (startServerOnFallback()) return;
                    String message = reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
                            ? "The phone has no Wi-Fi channel available. Turn off other hotspots, or use Wi-Fi mode."
                            : "Android could not start a hotspot (error " + reason + "). Use Wi-Fi mode, or turn off the phone's hotspot and try again.";
                    publish(message, null, null, null, null, null);
                    stopForegroundCompat();
                    stopSelf();
                }
            }, handler);
        } catch (SecurityException | IllegalStateException error) {
            starting = false;
            if (startServerOnFallback()) return;
            publish("Could not start the hotspot: " + error.getMessage(), null, null, null, null, null);
            stopForegroundCompat();
            stopSelf();
        }
    }

    /** Shares over an access point the phone already has, else over its Wi-Fi network. */
    private boolean startServerOnFallback() {
        String accessPoint = findAccessPointAddress();
        if (accessPoint != null) {
            beginServer(accessPoint, MODE_HOTSPOT, HOTSPOT_SSID_HINT, HOTSPOT_PASSWORD_HINT,
                    "Your phone's hotspot is already on, so PocketBridge is sharing over it. Connect the laptop to that hotspot, then open the link and enter the code.");
            return true;
        }
        String wifiAddress = findWifiClientAddress();
        if (wifiAddress != null) {
            beginServer(wifiAddress, MODE_WIFI, WIFI_SSID_HINT, WIFI_PASSWORD_HINT,
                    "No hotspot could be created, so PocketBridge is sharing over the Wi-Fi network this phone is on. Keep the laptop on that network, then open the link and enter the code.");
            return true;
        }
        return false;
    }

    private void beginServer(String address, String mode, String ssidHint, String passwordHint, String message) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String code = newCode();
        try {
            closeServer();
            server = new MediaHttpServer(this, code, token);
            server.start();
            usingFallback = true;
            currentToken = token;
            currentCode = code;
            currentMode = mode;
            currentSsid = ssidHint;
            currentPassword = passwordHint;
            // Short link: no long token to type. The code is entered on the page.
            currentUrl = "http://" + address + ":43117";
            publish(message, currentSsid, currentPassword, currentUrl, currentCode, currentMode);
            updateNotification("Link " + address + ":43117 · code " + code);
        } catch (Exception error) {
            closeServer();
            usingFallback = false;
            currentCode = null;
            currentUrl = null;
            publish("Could not start the local file server: " + error.getMessage(), null, null, null, null, null);
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void findAddressAndStartServer(int attempt) {
        if (reservation == null) return;
        String address = findHotspotAddress();
        if (address == null && attempt < 18) {
            handler.postDelayed(() -> findAddressAndStartServer(attempt + 1), 700);
            return;
        }
        if (address == null) {
            publish("Hotspot is on, but Android did not expose its address. Try Wi-Fi mode instead.", currentSsid, currentPassword, null, null, MODE_HOTSPOT);
            return;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String code = newCode();
        try {
            closeServer();
            server = new MediaHttpServer(this, code, token);
            server.start();
            usingFallback = false;
            currentToken = token;
            currentCode = code;
            currentMode = MODE_HOTSPOT;
            currentUrl = "http://" + address + ":43117";
            publish("Ready. Connect the laptop to this hotspot, open the link, then enter the code.", currentSsid, currentPassword, currentUrl, currentCode, currentMode);
            updateNotification("Link " + address + ":43117 · code " + code);
        } catch (Exception error) {
            closeServer();
            currentCode = null;
            currentUrl = null;
            publish("Could not start the local file server: " + error.getMessage(), currentSsid, currentPassword, null, null, MODE_HOTSPOT);
        }
    }

    private static String newCode() {
        SecureRandom random = new SecureRandom();
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    private String findHotspotAddress() {
        String internetWifi = null;
        try {
            ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network[] networks = connectivity.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                boolean internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                LinkProperties properties = connectivity.getLinkProperties(network);
                if (properties == null) continue;
                for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                    if (!(link.getAddress() instanceof Inet4Address) || !link.getAddress().isSiteLocalAddress()) continue;
                    String ip = link.getAddress().getHostAddress();
                    if (!internet) return ip;
                    if (internetWifi == null) internetWifi = ip;
                }
            }
        } catch (Exception ignored) { }
        String accessPoint = findAccessPointAddress();
        if (accessPoint != null) return accessPoint;
        return internetWifi;
    }

    /**
     * Finds the phone's own access-point address. Hotspot interfaces end in .1
     * (for example 192.168.43.1), while a Wi-Fi client address does not, so the
     * .1 rule avoids handing the laptop the phone's home-network address.
     */
    private String findAccessPointAddress() {
        String accessPointCandidate = null;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.ROOT);
                boolean accessPointLike = isAccessPointInterface(name);
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress() || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip.endsWith(".1")) return ip;
                    if (accessPointLike && accessPointCandidate == null) accessPointCandidate = ip;
                }
            }
        } catch (Exception ignored) { }
        return accessPointCandidate;
    }

    /** The phone's own Wi-Fi address, used when both devices share one Wi-Fi network. */
    private String findWifiClientAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.ROOT);
                if (!name.startsWith("wlan") || isAccessPointInterface(name)) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean isAccessPointInterface(String name) {
        if (name.isEmpty()) return false;
        if (name.startsWith("ap") || name.contains("softap") || name.startsWith("swlan")) return true;
        if (name.startsWith("wlan") && name.length() > 4) {
            try { return Integer.parseInt(name.substring(4)) > 0; } catch (NumberFormatException ignored) { return false; }
        }
        return name.startsWith("eth") || name.startsWith("rndis");
    }

    private void publish(String status, String ssid, String password, String url, String code, String mode) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_SSID, ssid);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_CODE, code);
        intent.putExtra(EXTRA_MODE, mode);
        sendBroadcast(intent);
        if (sessionActive()) updateNotification(status);
    }

    private void stopSession() {
        closeServer();
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
        usingFallback = false;
        currentSsid = null;
        currentPassword = null;
        currentUrl = null;
        currentCode = null;
        currentMode = null;
        currentToken = null;
        starting = false;
        publish("Share stopped. Your files are no longer available.", null, null, null, null, null);
        stopForegroundCompat();
        stopSelf();
    }

    private void closeServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PocketBridge share session", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while the private sharing hotspot is active.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("PocketBridge is sharing locally").setContentText(text).setContentIntent(pending).setOngoing(true).build();
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override public void onDestroy() {
        closeServer();
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
        usingFallback = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
