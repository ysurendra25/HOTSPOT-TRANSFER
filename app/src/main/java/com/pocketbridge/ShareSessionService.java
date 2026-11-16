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
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public final class ShareSessionService extends Service {
    public static final String ACTION_START = "com.pocketbridge.START";
    public static final String ACTION_STOP = "com.pocketbridge.STOP";
    public static final String ACTION_QUERY = "com.pocketbridge.QUERY";
    public static final String ACTION_STATUS = "com.pocketbridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_URL = "url";
    private static final String CHANNEL_ID = "pocketbridge_share";
    private static final int NOTIFICATION_ID = 1201;
    // Shown in the "connect your laptop" card when PocketBridge is borrowing a
    // hotspot the user already turned on from Android settings.
    private static final String EXISTING_SSID_HINT = "Use the hotspot already on this phone";
    private static final String EXISTING_PASSWORD_HINT = "Keep the laptop on that same Wi-Fi";
    private static final String SAME_WIFI_SSID_HINT = "Join the same Wi-Fi as this phone";
    private static final String SAME_WIFI_PASSWORD_HINT = "Use that network's usual password";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private MediaHttpServer server;
    private String currentSsid;
    private String currentPassword;
    private String currentUrl;
    private boolean starting;
    /** True when we serve over an existing system hotspot instead of owning one. */
    private boolean usingExistingHotspot;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private boolean sessionActive() {
        return reservation != null || usingExistingHotspot;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            String status = sessionActive() ? "Local share is active" : starting ? "Starting a local-only hotspot…" : "No share is active. Tap Start local share when ready.";
            publish(status, currentSsid, currentPassword, currentUrl);
            // A status query must not leave an idle service running forever.
            if (!sessionActive() && !starting) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !sessionActive() && !starting) startSession();
        return START_STICKY;
    }

    private void startSession() {
        starting = true;
        startForegroundCompat("Starting private hotspot…");
        publish("Starting a local-only hotspot…", null, null, null);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    reservation = value;
                    starting = false;
                    WifiConfiguration config = value.getWifiConfiguration();
                    currentSsid = config == null ? "PocketBridge" : config.SSID;
                    currentPassword = config == null ? "See Android Wi-Fi settings" : config.preSharedKey;
                    publish("Hotspot ready. Looking up the browser link…", currentSsid, currentPassword, null);
                    findAddressAndStartServer(0);
                }

                @Override public void onStopped() {
                    closeServer();
                    reservation = null;
                    starting = false;
                    currentUrl = null;
                    publish("The hotspot stopped. Start a new share when ready.", null, null, null);
                    stopSelf();
                }

                @Override public void onFailed(int reason) {
                    starting = false;
                    // Android refuses to create a local-only hotspot while the phone's
                    // own hotspot/tethering is already running. That is the common
                    // "hotspot is on but file sharing does nothing" case: serve over
                    // the hotspot the user already has instead of giving up.
                    if (reason == WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE
                            || reason == WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED
                            || reason == WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC) {
                        if (startServerOnExistingHotspot()) return;
                    }
                    String message = reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
                            ? "The phone has no Wi-Fi channel available. Close another hotspot and try again."
                            : "Android could not start its local-only hotspot (error " + reason + "). Turn off the phone's hotspot in Settings, then try again.";
                    publish(message, null, null, null);
                    stopForegroundCompat();
                    stopSelf();
                }
            }, handler);
        } catch (SecurityException | IllegalStateException error) {
            starting = false;
            publish("Could not start the hotspot: " + error.getMessage(), null, null, null);
            stopForegroundCompat();
            stopSelf();
        }
    }

    /**
     * Serves files over a hotspot the user already enabled from Android settings.
     * Returns false when no usable access-point address can be found.
     */
    private boolean startServerOnExistingHotspot() {
        String address = findAccessPointAddress();
        boolean overHotspot = address != null;
        // Nothing on this phone is acting as an access point, so fall back to the
        // Wi-Fi network the phone is already joined to.
        if (address == null) address = findWifiClientAddress();
        if (address == null) return false;
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            server = new MediaHttpServer(this, token);
            server.start();
            usingExistingHotspot = true;
            currentSsid = overHotspot ? EXISTING_SSID_HINT : SAME_WIFI_SSID_HINT;
            currentPassword = overHotspot ? EXISTING_PASSWORD_HINT : SAME_WIFI_PASSWORD_HINT;
            currentUrl = "http://" + address + ":43117/share/" + token;
            publish(overHotspot
                    ? "Your phone's hotspot is already on, so PocketBridge is sharing over it. Connect the laptop to that hotspot, then open the link."
                    : "Android would not start a private hotspot, so PocketBridge is sharing over the Wi-Fi network this phone is already on. Connect the laptop to the same Wi-Fi, then open the link.",
                    currentSsid, currentPassword, currentUrl);
            updateNotification("Browser link ready · " + address + ":43117");
            return true;
        } catch (Exception error) {
            closeServer();
            usingExistingHotspot = false;
            return false;
        }
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

    private void findAddressAndStartServer(int attempt) {
        if (reservation == null) return;
        String address = findHotspotAddress();
        if (address == null && attempt < 18) {
            handler.postDelayed(() -> findAddressAndStartServer(attempt + 1), 700);
            return;
        }
        if (address == null) {
            publish("Hotspot is on, but Android did not expose its local address. Turn hotspot off and try again.", currentSsid, currentPassword, null);
            return;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            server = new MediaHttpServer(this, token);
            server.start();
            currentUrl = "http://" + address + ":43117/share/" + token;
            publish("Ready. Connect the laptop to this hotspot, then open the link.", currentSsid, currentPassword, currentUrl);
            updateNotification("Browser link ready · " + address + ":43117");
        } catch (Exception error) {
            closeServer();
            publish("Could not start the local file server: " + error.getMessage(), currentSsid, currentPassword, null);
        }
    }

    private String findHotspotAddress() {
        // Prefer the local-only hotspot network when Android also has the phone
        // joined to another Wi-Fi network, then fall back to interface scanning.
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
                    // A hotspot network has no internet capability. Keep an internet
                    // Wi-Fi address only as a last resort.
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

    private static boolean isAccessPointInterface(String name) {
        if (name.isEmpty()) return false;
        if (name.startsWith("ap") || name.contains("softap") || name.startsWith("swlan")) return true;
        // Secondary WLAN interfaces (wlan1, wlan2, …) host the access point when
        // wlan0 is already used as a Wi-Fi client.
        if (name.startsWith("wlan") && name.length() > 4) {
            try { return Integer.parseInt(name.substring(4)) > 0; } catch (NumberFormatException ignored) { return false; }
        }
        return name.startsWith("eth") || name.startsWith("rndis");
    }

    private void publish(String status, String ssid, String password, String url) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_SSID, ssid);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_URL, url);
        sendBroadcast(intent);
        if (sessionActive()) updateNotification(status);
    }

    private void stopSession() {
        closeServer();
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
        usingExistingHotspot = false;
        currentSsid = null;
        currentPassword = null;
        currentUrl = null;
        starting = false;
        publish("Share stopped. Your files are no longer available on the hotspot.", null, null, null);
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
        usingExistingHotspot = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
