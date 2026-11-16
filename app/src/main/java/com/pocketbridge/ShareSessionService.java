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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.ServiceInfo;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private MediaHttpServer server;
    private String currentSsid;
    private String currentPassword;
    private String currentUrl;
    private boolean starting;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_QUERY : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            String status = reservation != null ? "Local share is active" : starting ? "Starting a local-only hotspot…" : "No share is active. Tap Start local share when ready.";
            publish(status, currentSsid, currentPassword, currentUrl);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && reservation == null && !starting) startSession();
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
                    String message = reason == ERROR_NO_CHANNEL ? "The phone has no Wi-Fi channel available. Close another hotspot and try again." : "Android could not start its local-only hotspot (error " + reason + ").";
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
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                boolean hotspotLike = network.getName().toLowerCase().contains("ap") || network.getName().toLowerCase().contains("wlan");
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        if (hotspotLike && ip.endsWith(".1")) return ip;
                    }
                }
            }
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void publish(String status, String ssid, String password, String url) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_SSID, ssid);
        intent.putExtra(EXTRA_PASSWORD, password);
        intent.putExtra(EXTRA_URL, url);
        sendBroadcast(intent);
        if (reservation != null) updateNotification(status);
    }

    private void stopSession() {
        closeServer();
        if (reservation != null) {
            try { reservation.close(); } catch (Exception ignored) { }
            reservation = null;
        }
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
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
