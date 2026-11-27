package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Wakes the app for an incoming camera request while it is in the background:
 * a high-priority notification with Accept and Decline. Accept opens the app,
 * which asks for Android's camera permission if needed before accepting.
 * Decline answers right away without opening anything.
 */
final class CameraCallNotifier {
    static final String CHANNEL_ID = "pocketbridge_camera_requests";
    static final int NOTIFICATION_ID = 1204;
    static final String ACTION_ACCEPT = "com.pocketbridge.action.CAMERA_ACCEPT";
    static final String ACTION_DECLINE = "com.pocketbridge.action.CAMERA_DECLINE";
    static final String EXTRA_CHANNEL = "camera_channel"; // "host" (this phone's server) or "peer" (another phone)
    static final String EXTRA_ID = "camera_call_id";

    /** True while MainActivity is in the foreground; it then shows the prompt itself. */
    static volatile boolean appVisible;

    private static volatile boolean installed;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private CameraCallNotifier() { }

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext();
        CameraCall.INSTANCE.addListener(call -> main.post(() -> update(app, "host", call.awaiting(CameraCall.PHONE),
                call.id(), call.remoteName(), call.expiresInMs())));
        PeerCameraLink.INSTANCE.addListener(link -> main.post(() -> update(app, "peer", link.awaitingUs(),
                link.callId(), "the other phone", link.status().optLong("expiresInMs", CameraCall.REQUEST_TIMEOUT_MS))));
    }

    /** Re-shows (or clears) the notifications for the current state, e.g. when the app leaves the foreground. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        CameraCall call = CameraCall.INSTANCE;
        PeerCameraLink link = PeerCameraLink.INSTANCE;
        update(app, "host", call.awaiting(CameraCall.PHONE), call.id(), call.remoteName(), call.expiresInMs());
        update(app, "peer", link.awaitingUs(), link.callId(), "the other phone",
                link.status().optLong("expiresInMs", CameraCall.REQUEST_TIMEOUT_MS));
    }

    private static void update(Context context, String channel, boolean incoming, String id, String name, long expiresInMs) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (!incoming) {
            manager.cancel(channel, NOTIFICATION_ID);
            return;
        }
        if (appVisible) return;
        createChannel(manager);
        String who = name == null || name.isEmpty() ? "A laptop or phone on your hotspot" : name;
        Intent accept = new Intent(context, MainActivity.class).setAction(ACTION_ACCEPT)
                .putExtra(EXTRA_CHANNEL, channel).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent decline = new Intent(context, Receiver.class).setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CHANNEL, channel).putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent acceptIntent = PendingIntent.getActivity(context, 21, accept, flags);
        PendingIntent declineIntent = PendingIntent.getBroadcast(context, 22, decline, flags);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Camera call request")
                .setContentText(who + " wants a two-way camera call. Nothing is shared until you accept.")
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(acceptIntent)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1000L, expiresInMs))
                .addAction(android.R.drawable.ic_menu_camera, "Accept", acceptIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
                .build();
        try { manager.notify(channel, NOTIFICATION_ID, notification); }
        catch (SecurityException noPermission) { /* Notifications are off; the in-app prompt still works. */ }
        // An unanswered request expires after 30 s; make the server notice even if nobody polls.
        if ("host".equals(channel)) main.postDelayed(CameraCall.INSTANCE::tick, Math.max(0L, expiresInMs) + 50L);
    }

    private static void createChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Camera call requests", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Asks before a nearby laptop or phone can start a camera call.");
        manager.createNotificationChannel(channel);
    }

    /** Decline straight from the notification. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_DECLINE.equals(intent.getAction())) return;
            String channel = intent.getStringExtra(EXTRA_CHANNEL);
            String id = intent.getStringExtra(EXTRA_ID);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(channel, NOTIFICATION_ID);
            if ("peer".equals(channel)) {
                PendingResult pending = goAsync();
                new Thread(() -> {
                    try { if (id != null && id.equals(PeerCameraLink.INSTANCE.callId())) PeerCameraLink.INSTANCE.respond(false); }
                    catch (Exception ignored) { }
                    finally { pending.finish(); }
                }).start();
            } else {
                try { CameraCall.INSTANCE.respond(CameraCall.PHONE, id, false); } catch (CameraCall.Refused ignored) { }
            }
        }
    }
}
