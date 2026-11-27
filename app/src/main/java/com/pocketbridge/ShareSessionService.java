package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    public static final String EXTRA_ALT = "alternates";
    public static final String EXTRA_DIAG = "diagnostics";
    public static final String EXTRA_ACTIVE = "active";
    private static final String CHANNEL_ID = "pocketbridge_share";
    private static final int NOTIFICATION_ID = 1201;
    private static final String MODE_WIFI = "Wi-Fi network";
    static final String MODE_HOTSPOT = "Phone hotspot";
    private static final String WIFI_PASSWORD_HINT = "Use your usual Wi-Fi password on the laptop, if asked";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService addressScanner = Executors.newSingleThreadExecutor();
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private MediaHttpServer server;
    private String currentSsid;
    private String currentPassword;
    private String currentUrl;
    private String currentCode;
    private String currentMode;
    private String currentAlt = "";
    private String currentDiag = "";
    private boolean starting;
    private int sessionGeneration;
    private int scanRevision;
    private String addressLead;
    private final Runnable refreshAddress = this::refreshCurrentAddress;
    private final Runnable retryHealth = () -> { if (currentUrl != null) verifyServer(); };
    /** True when we serve without owning a local-only hotspot. */
    private boolean usingFallback;

    /** One IPv4 address on this phone, with the interface it came from. */
    static final class Addr {
        final String ip;
        final String name;
        Addr(String ip, String name) { this.ip = ip; this.name = name; }
    }

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
            String status;
            if (starting) status = "Starting…";
            else if (!sessionActive()) status = "No share is active. Pick a mode to begin.";
            else if (currentUrl == null) status = "Waiting for a local network address…";
            else status = "Sharing over " + currentMode.toLowerCase(Locale.ROOT);
            publish(status, currentSsid, currentPassword, currentUrl, currentCode, currentMode, currentAlt, currentDiag);
            // stopSelf(startId) only stops if no newer command arrived, so a status
            // query can never kill a share that started a moment later.
            if (!sessionActive() && !starting) stopSelf(startId);
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
        ++sessionGeneration;
        starting = true;
        startForegroundCompat("Starting Wi-Fi sharing…");
        publish("Starting the file server…", null, null, null, null, MODE_WIFI, "", "");
        if (!startServer()) { starting = false; failServer(); return; }
        starting = false;
        usingFallback = true;
        resolveAndPublish(MODE_WIFI, describeWifiNetwork(), WIFI_PASSWORD_HINT,
                "Ready. Keep the laptop on the same Wi-Fi as this phone, open the link, then enter the code.");
    }

    /**
     * Mode 2: create a private local-only hotspot. If Android refuses because the
     * phone's own hotspot is already running, share over that one instead.
     */
    private void startHotspotSession() {
        final int generation = ++sessionGeneration;
        starting = true;
        startForegroundCompat("Starting private hotspot…");
        publish("Starting the file server…", null, null, null, null, MODE_HOTSPOT, "", "");
        // Bring the server up first so a hotspot problem can never leave us with
        // nothing to show.
        if (!startServer()) { starting = false; failServer(); return; }
        usingFallback = true;
        scanAddresses(addresses -> {
            // A laptop already joined to Android's own hotspot must stay on that
            // same AP, even on phones that can create a second local-only AP.
            if (selectAddress(addresses, true) != null) {
                starting = false;
                useExistingHotspot("Using the phone’s existing hotspot.");
                return;
            }
            requestLocalHotspot(generation);
        });
    }

    private void requestLocalHotspot(int generation) {
        publish("Starting a local-only hotspot…", null, null, null, null, MODE_HOTSPOT, "", "");
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            starting = false;
            useExistingHotspot("The Wi-Fi hotspot service is unavailable.");
            return;
        }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation value) {
                    if (generation != sessionGeneration) { value.close(); return; }
                    reservation = value;
                    HotspotBand.localHotspotBand = HotspotBand.UNKNOWN;
                    starting = false;
                    String ssid = null;
                    String password = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.net.wifi.SoftApConfiguration config = value.getSoftApConfiguration();
                        if (config != null) {
                            ssid = config.getSsid();
                            password = config.getPassphrase();
                            HotspotBand.localHotspotBand = HotspotBand.fromSoftApConfiguration(config);
                        }
                    } else {
                        android.net.wifi.WifiConfiguration config = value.getWifiConfiguration();
                        if (config != null) {
                            ssid = config.SSID;
                            password = config.preSharedKey;
                            try { HotspotBand.localHotspotBand = HotspotBand.fromLegacyApBand(config.getClass().getField("apBand").getInt(config)); }
                            catch (Throwable hidden) { HotspotBand.localHotspotBand = HotspotBand.UNKNOWN; }
                        }
                    }
                    currentSsid = ssid == null || ssid.isEmpty() ? "See Android Hotspot settings" : ssid;
                    currentPassword = password == null || password.isEmpty() ? "See Android Wi-Fi settings" : password;
                    publish("Hotspot ready. Looking up the browser link…", currentSsid, currentPassword, null, null, MODE_HOTSPOT, "", "");
                    resolveAndPublish(MODE_HOTSPOT, currentSsid, currentPassword,
                            "Connect the laptop to this hotspot and open the address below.");
                }

                @Override public void onStopped() {
                    if (generation != sessionGeneration) return;
                    reservation = null;
                    stopSession();
                }

                @Override public void onFailed(int reason) {
                    if (generation != sessionGeneration) return;
                    starting = false;
                    // The server is already running, so share over whatever network
                    // this phone still has instead of showing nothing.
                    String note = reason == WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
                            ? "Android had no free Wi-Fi channel to create a private hotspot."
                            : "Android could not create a private hotspot (error " + reason + ").";
                    useExistingHotspot(note);
                }
            }, handler);
            handler.postDelayed(() -> {
                if (generation == sessionGeneration && starting) {
                    starting = false;
                    useExistingHotspot("Android has not reported a new hotspot yet.");
                }
            }, 8000);
        } catch (SecurityException | IllegalStateException | UnsupportedOperationException error) {
            starting = false;
            useExistingHotspot("Android could not create a private hotspot.");
        }
    }

    /** Starts the HTTP server on every interface. Returns false only on a bind error. */
    private boolean startServer() {
        try {
            closeServer();
            String code = newCode();
            String token = UUID.randomUUID().toString().replace("-", "");
            server = new MediaHttpServer(this, code, token);
            server.start();
            currentCode = code;
            return true;
        } catch (Exception error) {
            closeServer();
            currentDiag = "bind error: " + error.getMessage();
            return false;
        }
    }

    private void failServer() {
        publish("Could not start the file server on this phone: " + valueOr(currentDiag, "unknown error"), null, null, null, null, null, "", currentDiag);
        stopForegroundCompat();
        stopSelf();
    }

    private void useExistingHotspot(String reason) {
        if (reservation == null) HotspotBand.localHotspotBand = HotspotBand.UNKNOWN;
        resolveAndPublish(MODE_HOTSPOT, "See your phone’s Hotspot settings", "Use the hotspot password from Settings",
                reason + " Join the phone’s hotspot from the laptop. If it is off, turn it on in Android Settings.");
    }

    private void refreshCurrentAddress() {
        if (server != null && currentMode != null) {
            resolveAndPublish(currentMode, currentSsid, currentPassword, addressLead);
        }
    }

    /**
     * Builds the link, the alternates and a short diagnostics line, then publishes
     * everything so the phone screen is never blank.
     */
    private void resolveAndPublish(String mode, String ssid, String password, String lead) {
        addressLead = lead;
        handler.removeCallbacks(refreshAddress);
        scanAddresses(addresses -> publishAddresses(mode, ssid, password, lead, addresses));
    }

    private void scanAddresses(Consumer<List<Addr>> callback) {
        int generation = sessionGeneration;
        int revision = ++scanRevision;
        addressScanner.execute(() -> {
            List<Addr> addresses = allAddresses();
            handler.post(() -> {
                if (generation == sessionGeneration && revision == scanRevision && server != null) callback.accept(addresses);
            });
        });
    }

    private void publishAddresses(String mode, String ssid, String password, String lead, List<Addr> addresses) {
        handler.postDelayed(refreshAddress, 5000);
        String primary = selectAddress(addresses, MODE_HOTSPOT.equals(mode));
        boolean sameMode = mode.equals(currentMode);
        if (primary == null) {
            boolean unchanged = currentUrl == null && sameMode && diagnostics(addresses, null).equals(currentDiag);
            currentUrl = null;
            currentSsid = ssid;
            currentPassword = password;
            currentMode = mode;
            currentAlt = "";
            currentDiag = diagnostics(addresses, null);
            if (!unchanged) {
                handler.removeCallbacks(retryHealth);
                publish(lead + " Waiting for a local Wi-Fi address…",
                        ssid, password, null, currentCode, mode, "", currentDiag);
            }
            return;
        }
        List<String> others = new ArrayList<>();
        for (Addr address : addresses) {
            if (!address.ip.equals(primary) && localRank(address.name, MODE_HOTSPOT.equals(mode)) > 0)
                others.add("http://" + address.ip + ":" + MediaHttpServer.PORT);
        }
        currentSsid = ssid;
        currentPassword = password;
        currentMode = mode;
        String nextUrl = "http://" + primary + ":" + MediaHttpServer.PORT;
        if (sameMode && nextUrl.equals(currentUrl) && String.join("\n", others).equals(currentAlt)) return;
        currentUrl = nextUrl;
        currentAlt = String.join("\n", others);
        currentDiag = diagnostics(addresses, primary);
        publish(lead, ssid, password, currentUrl, currentCode, mode, currentAlt, currentDiag);
        updateNotification("Link " + primary + ":" + MediaHttpServer.PORT + " · code " + currentCode);
        verifyServer();
    }

    /** Confirms the HTTP server really answers on this phone. */
    private void verifyServer() {
        handler.removeCallbacks(retryHealth);
        final String url = currentUrl;
        final String code = currentCode;
        final String mode = currentMode;
        final String ssid = currentSsid;
        final String password = currentPassword;
        final String alt = currentAlt;
        final String diag = currentDiag;
        new Thread(() -> {
            boolean healthy = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url + "/health").openConnection(java.net.Proxy.NO_PROXY);
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                healthy = connection.getResponseCode() == 200;
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            final boolean ok = healthy;
            handler.post(() -> {
                if (!sessionActive() || !url.equals(currentUrl) || !code.equals(currentCode)) return;
                String text = ok
                        ? addressLead
                        : "Checking the hotspot link again. Keep both devices connected.";
                if (!ok) handler.postDelayed(retryHealth, 5000);
                publish(text, ssid, password, url, code, mode, alt, diag);
            });
        }, "PocketBridge-selftest").start();
    }

    private static String newCode() {
        SecureRandom random = new SecureRandom();
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    /** Real Wi-Fi name when Android allows reading it, else a clear description. */
    private String describeWifiNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                for (Network network : connectivity.getAllNetworks()) {
                    android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                    if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    Object info = capabilities.getTransportInfo();
                    if (info instanceof WifiInfo) {
                        String ssid = cleanSsid(((WifiInfo) info).getSSID());
                        if (ssid != null) return ssid;
                    }
                }
            }
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            String ssid = cleanSsid(info == null ? null : info.getSSID());
            if (ssid != null) return ssid;
        } catch (Exception ignored) { }
        return Build.VERSION.SDK_INT >= 33
                ? "Allow Nearby Wi-Fi access to show this network name"
                : "Allow location access to show this network name";
    }

    private static String cleanSsid(String value) {
        if (value == null) return null;
        String ssid = value.trim();
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        String lower = ssid.toLowerCase(Locale.ROOT);
        return !ssid.isEmpty() && !lower.contains("unknown") && !"0x".equals(ssid) ? ssid : null;
    }

    /**
     * Every IPv4 address this phone has, gathered three independent ways so a
     * vendor-specific interface name can never leave the list empty.
     */
    private List<Addr> allAddresses() {
        Set<String> seen = new LinkedHashSet<>();
        List<Addr> addresses = new ArrayList<>();

        // 1. Directly from the Wi-Fi service. Works without interface names and
        //    without location permission.
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            int raw = info == null ? 0 : info.getIpAddress();
            if (raw != 0) {
                String ip = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        (raw & 0xff), (raw >> 8) & 0xff, (raw >> 16) & 0xff, (raw >> 24) & 0xff);
                if (!"0.0.0.0".equals(ip) && seen.add(ip)) addresses.add(new Addr(ip, "wifi"));
            }
        } catch (Exception ignored) { }

        // 2. Link properties of every network Android knows about.
        try {
            ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network network : connectivity.getAllNetworks()) {
                LinkProperties properties = connectivity.getLinkProperties(network);
                if (properties == null) continue;
                String name = properties.getInterfaceName();
                android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
                if (caps != null) {
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) lowerName = "vpn";
                    else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) lowerName = "wifi";
                    else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) lowerName = "cellular";
                }
                for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                    if (!(link.getAddress() instanceof Inet4Address)) continue;
                    String ip = link.getAddress().getHostAddress();
                    if (ip.startsWith("127.") || !seen.add(ip)) continue;
                    addresses.add(new Addr(ip, lowerName));
                }
            }
        } catch (Exception ignored) { }

        // 3. Raw interface scan.
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.ROOT);
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    String ip = address.getHostAddress();
                    if (ip.startsWith("127.")) continue;
                    // WifiInfo sometimes reports the AP's own IP as a client IP.
                    // Prefer positive evidence from its tethering interface.
                    if (!seen.add(ip)) {
                        if (!isAccessPointInterface(name)) continue;
                        addresses.removeIf(a -> a.ip.equals(ip));
                    }
                    addresses.add(new Addr(ip, name));
                }
            }
        } catch (Exception ignored) { }

        return addresses;
    }

    static String selectAddress(List<Addr> addresses, boolean hotspot) {
        int best = 0;
        String selected = null;
        for (Addr address : addresses) {
            int rank = localRank(address.name, hotspot);
            if (rank > best) {
                selected = address.ip;
                best = rank;
            }
        }
        return selected;
    }

    // The tethering adapter can have any host number on Android 15. An IP
    // ending in .1 is also common on cellular and VPN interfaces.
    static boolean isAccessPointInterface(String name) {
        return name.equals("ap") || name.matches("ap[0-9]+")
                || name.startsWith("ap_br_") || name.startsWith("br_wlan") || name.startsWith("ap_wlan")
                || name.startsWith("wlan_softap") || name.startsWith("softap") || name.startsWith("br_softap")
                || name.startsWith("wlan_ap") || name.startsWith("swlan");
    }

    private static int localRank(String name, boolean hotspot) {
        if (isAccessPointInterface(name)) return hotspot ? 4 : 1;
        if (name.equals("wifi")) return hotspot ? 0 : 4; // Android's connected client network
        if (name.matches("wlan[0-9]+")) {
            if (hotspot && !name.equals("wlan0")) return 3;
            return hotspot ? 2 : 3;
        }
        if (name.matches("wifi[0-9]+")) return hotspot ? 2 : 3;
        if (!hotspot && name.startsWith("eth")) return 1;
        return 0;
    }

    private String diagnostics(List<Addr> addresses, String primary) {
        StringBuilder builder = new StringBuilder();
        builder.append("server: ").append(server != null ? "listening on 0.0.0.0:" + MediaHttpServer.PORT : "not running");
        builder.append(" · chosen: ").append(primary == null ? "none" : primary);
        if (addresses.isEmpty()) {
            builder.append(" · no IPv4 address found");
        } else {
            builder.append(" · found: ");
            for (int i = 0; i < addresses.size(); i++) {
                if (i > 0) builder.append(", ");
                builder.append(addresses.get(i).name.isEmpty() ? "?" : addresses.get(i).name)
                        .append('=').append(addresses.get(i).ip);
            }
        }
        return builder.toString();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private void publish(String status, String ssid, String password, String url, String code,
                         String mode, String alternates, String diagnostics) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ACTIVE, starting || sessionActive());
        intent.putExtra(EXTRA_SSID, ssid);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_URL, url);
        intent.putExtra(EXTRA_CODE, code);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_ALT, alternates);
        intent.putExtra(EXTRA_DIAG, diagnostics);
        sendBroadcast(intent);
        if (sessionActive()) updateNotification(status);
    }

    private void stopSession() {
        ++sessionGeneration;
        handler.removeCallbacks(refreshAddress);
        handler.removeCallbacks(retryHealth);
        // Screen capture belongs to the share: do not leave the screen streaming
        // (and the capture notification showing) after the share has stopped.
        stopService(new Intent(this, ScreenCaptureService.class));
        stopService(new Intent(this, CameraSessionService.class));
        closeServer();
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
        HotspotBand.localHotspotBand = HotspotBand.UNKNOWN;
        usingFallback = false;
        currentSsid = null;
        currentPassword = null;
        currentUrl = null;
        currentCode = null;
        currentMode = null;
        currentAlt = "";
        currentDiag = "";
        starting = false;
        publish("Share stopped. Your files are no longer available.", null, null, null, null, null, "", "");
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
        ++sessionGeneration;
        handler.removeCallbacks(refreshAddress);
        handler.removeCallbacks(retryHealth);
        addressScanner.shutdownNow();
        if (server != null) {
            stopService(new Intent(this, ScreenCaptureService.class));
            stopService(new Intent(this, CameraSessionService.class));
        }
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
