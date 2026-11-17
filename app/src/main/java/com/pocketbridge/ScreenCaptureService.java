package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.content.pm.ServiceInfo;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpParameters;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ScreenCaptureService extends Service {
    static final String EXTRA_RESULT_CODE = "projection_result_code";
    static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String CHANNEL_ID = "pocketbridge_screen";
    private static final int NOTIFICATION_ID = 1202;
    private static volatile ScreenCaptureService instance;
    private static volatile String lastSetupError;
    private static volatile String lastOfferError;
    private static volatile String lastIceState = "new";

    private EglBase eglBase;
    private volatile PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private volatile VideoTrack videoTrack;
    private volatile PeerConnection peer;
    private volatile RtpSender sender;
    private volatile String pendingAnswer;
    private volatile CountDownLatch answerLatch;
    private volatile boolean iceGatheringComplete;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DisplayManager.DisplayListener displayListener;
    private int captureWidth;
    private int captureHeight;

    public static boolean isRunning() { return instance != null && instance.videoTrack != null; }

    public static String getSetupError() { return lastSetupError; }

    public static String getIceState() { return lastIceState; }

    public static String getLastOfferError() { return lastOfferError; }

    public static String answerOffer(String sdp) {
        ScreenCaptureService service = instance;
        if (service == null || service.videoTrack == null || sdp == null || sdp.isEmpty()) return null;
        try {
            return service.acceptOffer(sdp);
        } catch (RuntimeException error) {
            lastOfferError = "The phone hit an error preparing screen sharing. Restart screen sharing and retry.";
            android.util.Log.e("PocketBridge", "Screen offer failed", error);
            service.closePeer();
            return null;
        }
    }

    public static void stopPeer() {
        ScreenCaptureService service = instance;
        if (service != null) service.closePeer();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getIntExtra(EXTRA_RESULT_CODE, 0) == 0) {
            lastSetupError = "Android screen-capture permission was not provided.";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            lastSetupError = null;
            // Always answer startForegroundService() promptly, even when a capture is
            // already running, or Android can stop the app for not calling it in time.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, buildNotification());
            if (isRunning()) return START_NOT_STICKY;
            Intent permissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (permissionData == null) throw new IllegalArgumentException("Android did not return screen-capture permission data.");
            initialize(permissionData);
        } catch (Exception error) {
            lastSetupError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            android.util.Log.e("PocketBridge", "Screen share setup failed", error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void initialize(Intent permissionData) throws Exception {
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(options);
        eglBase = EglBase.create();
        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        // Marking the source as a screencast keeps text sharp (resolution is kept,
        // frame rate drops first) and repeats the last frame while the screen is still.
        videoSource = factory.createVideoSource(true);
        surfaceTextureHelper = SurfaceTextureHelper.create("PocketBridge-screen", eglBase.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(permissionData, new android.media.projection.MediaProjection.Callback() {
            @Override public void onStop() {
                android.util.Log.i("PocketBridge", "Screen capture stopped by Android");
                stopSelf();
            }
        });
        capturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        int[] size = captureSize();
        capturer.startCapture(size[0], size[1], 30);
        captureWidth = size[0];
        captureHeight = size[1];
        videoTrack = factory.createVideoTrack("pocketbridge-screen", videoSource);
        videoTrack.setEnabled(true);
        registerDisplayListener();
    }

    /** Capture size for the current screen orientation, at most 1920 pixels on the long side. */
    private int[] captureSize() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        float scale = Math.min(1f, 1920f / Math.max(width, height));
        width = Math.max(2, Math.round(width * scale / 2f) * 2);
        height = Math.max(2, Math.round(height * scale / 2f) * 2);
        return new int[]{width, height};
    }

    /** Follows phone rotation. A fixed capture size would otherwise show a squashed picture. */
    private void registerDisplayListener() {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return;
        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) applyDisplaySize();
            }
        };
        manager.registerDisplayListener(displayListener, mainHandler);
    }

    private void applyDisplaySize() {
        ScreenCapturerAndroid active = capturer;
        if (active == null) return;
        int[] size = captureSize();
        if (size[0] == captureWidth && size[1] == captureHeight) return;
        captureWidth = size[0];
        captureHeight = size[1];
        try { active.changeCaptureFormat(size[0], size[1], 30); }
        catch (Exception error) { android.util.Log.w("PocketBridge", "Could not resize screen capture", error); }
    }

    /**
     * Android only delivers a screen frame when something on screen changes, so a
     * browser that connects to a still screen would wait for a frame that never
     * comes. Recreating the capture surface produces a fresh frame straight away.
     */
    private void refreshCapture() {
        ScreenCapturerAndroid active = capturer;
        if (active == null || captureWidth == 0 || captureHeight == 0) return;
        try { active.changeCaptureFormat(captureWidth, captureHeight, 30); }
        catch (Exception error) { android.util.Log.w("PocketBridge", "Could not refresh screen capture", error); }
    }

    private String acceptOffer(String offerSdp) {
        closePeer();
        lastOfferError = null;
        pendingAnswer = null;
        iceGatheringComplete = false;
        lastIceState = "new";
        CountDownLatch latch = new CountDownLatch(1);
        answerLatch = latch;
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection activePeer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                lastIceState = state == null ? "unknown" : state.toString().toLowerCase(java.util.Locale.ROOT);
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    // Once now, and once more after the encoder and DTLS are surely ready.
                    mainHandler.post(ScreenCaptureService.this::refreshCapture);
                    mainHandler.postDelayed(ScreenCaptureService.this::refreshCapture, 1200);
                }
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete = true;
                    maybeCompleteAnswer();
                }
            }
            @Override public void onIceCandidate(IceCandidate candidate) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            @Override public void onAddStream(org.webrtc.MediaStream stream) { }
            @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
            @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) { }
        });
        peer = activePeer;
        if (activePeer == null) {
            lastOfferError = "The phone could not create a WebRTC connection. Close and restart screen sharing.";
            answerLatch = null;
            return null;
        }
        sender = activePeer.addTrack(videoTrack, Collections.singletonList("pocketbridge"));
        if (sender != null) {
            try {
                RtpParameters parameters = sender.getParameters();
                for (RtpParameters.Encoding encoding : parameters.encodings) encoding.maxBitrateBps = 8_000_000;
                sender.setParameters(parameters);
            } catch (Exception ignored) { }
        }
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
        activePeer.setRemoteDescription(new EmptySdpObserver() {
            @Override public void onSetSuccess() {
                activePeer.createAnswer(new EmptySdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription answer) {
                        activePeer.setLocalDescription(new EmptySdpObserver() {
                            @Override public void onSetSuccess() { maybeCompleteAnswer(); }
                            @Override public void onSetFailure(String error) { failAnswer("Could not set the local WebRTC description: " + error); }
                        }, answer);
                    }
                    @Override public void onCreateFailure(String error) { failAnswer("Could not create a WebRTC answer: " + error); }
                }, new MediaConstraints());
            }
            @Override public void onSetFailure(String error) { failAnswer("Could not read the browser's WebRTC offer: " + error); }
        }, offer);
        try {
            if (!latch.await(32, TimeUnit.SECONDS)) {
                lastOfferError = "Local screen connection setup timed out. Confirm both devices use the same PocketBridge Wi-Fi and retry.";
                closePeer();
                return null;
            }
            if (pendingAnswer == null || pendingAnswer.isEmpty()) {
                if (lastOfferError == null) lastOfferError = "The phone could not finish its screen-sharing answer. Restart screen sharing and retry.";
                closePeer();
                return null;
            }
            return pendingAnswer;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lastOfferError = "Screen connection setup was interrupted. Start screen sharing again.";
            return null;
        } finally { answerLatch = null; }
    }

    private void maybeCompleteAnswer() {
        if (!iceGatheringComplete || peer == null || answerLatch == null) return;
        SessionDescription description = peer.getLocalDescription();
        if (description == null) return;
        pendingAnswer = description.description;
        answerLatch.countDown();
    }

    private void failAnswer(String message) {
        android.util.Log.e("PocketBridge", message);
        lastOfferError = message;
        pendingAnswer = "";
        CountDownLatch latch = answerLatch;
        if (latch != null) latch.countDown();
    }

    private void closePeer() {
        if (peer != null) {
            try { peer.close(); } catch (Exception ignored) { }
            try { peer.dispose(); } catch (Exception ignored) { }
            peer = null;
        }
        sender = null;
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        try {
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (manager != null && displayListener != null) manager.unregisterDisplayListener(displayListener);
        } catch (Exception ignored) { }
        closePeer();
        try { if (capturer != null) { capturer.stopCapture(); capturer.dispose(); } } catch (Exception ignored) { }
        try { if (videoTrack != null) videoTrack.dispose(); } catch (Exception ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) { }
        try { if (surfaceTextureHelper != null) surfaceTextureHelper.dispose(); } catch (Exception ignored) { }
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) { }
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) { }
        instance = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PocketBridge screen share", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while your phone screen is being shared.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("PocketBridge screen share is active")
                .setContentText("Your screen is being streamed to the local browser.").setContentIntent(pending).setOngoing(true).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class EmptySdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
