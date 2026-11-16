package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
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

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private PeerConnection peer;
    private RtpSender sender;
    private volatile String pendingAnswer;
    private volatile CountDownLatch answerLatch;
    private volatile boolean iceGatheringComplete;

    public static boolean isRunning() { return instance != null && instance.videoTrack != null; }

    public static String answerOffer(String sdp) {
        ScreenCaptureService service = instance;
        if (service == null || service.videoTrack == null || sdp == null || sdp.isEmpty()) return null;
        return service.acceptOffer(sdp);
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
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID, buildNotification());
        Intent permissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (permissionData == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            initialize(permissionData);
        } catch (Exception error) {
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
        videoSource = factory.createVideoSource(false);
        surfaceTextureHelper = SurfaceTextureHelper.create("PocketBridge-screen", eglBase.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(permissionData, new android.media.projection.MediaProjection.Callback() {
            @Override public void onStop() {
                android.util.Log.i("PocketBridge", "Screen capture stopped by Android");
                stopSelf();
            }
        });
        capturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        float scale = Math.min(1f, 1920f / Math.max(width, height));
        width = Math.max(2, Math.round(width * scale / 2f) * 2);
        height = Math.max(2, Math.round(height * scale / 2f) * 2);
        capturer.startCapture(width, height, 30);
        videoTrack = factory.createVideoTrack("pocketbridge-screen", videoSource);
        videoTrack.setEnabled(true);
    }

    private String acceptOffer(String offerSdp) {
        closePeer();
        pendingAnswer = null;
        iceGatheringComplete = false;
        CountDownLatch latch = new CountDownLatch(1);
        answerLatch = latch;
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
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
        if (peer == null) { answerLatch = null; return null; }
        sender = peer.addTrack(videoTrack, Collections.singletonList("pocketbridge"));
        if (sender != null) {
            try {
                RtpParameters parameters = sender.getParameters();
                for (RtpParameters.Encoding encoding : parameters.encodings) encoding.maxBitrateBps = 8_000_000;
                sender.setParameters(parameters);
            } catch (Exception ignored) { }
        }
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
        peer.setRemoteDescription(new EmptySdpObserver() {
            @Override public void onSetSuccess() {
                peer.createAnswer(new EmptySdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription answer) {
                        peer.setLocalDescription(new EmptySdpObserver() {
                            @Override public void onSetSuccess() { maybeCompleteAnswer(); }
                        }, answer);
                    }
                }, new MediaConstraints());
            }
            @Override public void onSetFailure(String error) { pendingAnswer = ""; latch.countDown(); }
        }, offer);
        try {
            if (!latch.await(18, TimeUnit.SECONDS)) return null;
            return pendingAnswer == null || pendingAnswer.isEmpty() ? null : pendingAnswer;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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

    private void closePeer() {
        if (peer != null) {
            try { peer.close(); } catch (Exception ignored) { }
            try { peer.dispose(); } catch (Exception ignored) { }
            peer = null;
        }
        sender = null;
    }

    @Override public void onDestroy() {
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
