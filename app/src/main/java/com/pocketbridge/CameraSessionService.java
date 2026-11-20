package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
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
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * User-started, direct WebRTC camera session. The local foreground service
 * keeps Android's camera indicator visible while the camera is in use. No
 * STUN/TURN servers are configured: the call is intended for the local link.
 */
public final class CameraSessionService extends Service {
    private static final String ACTION_STOP = "com.pocketbridge.action.STOP_CAMERA";
    private static final String CHANNEL_ID = "pocketbridge_camera";
    private static final int NOTIFICATION_ID = 1203;
    private static final int CAMERA_WIDTH = 1280;
    private static final int CAMERA_HEIGHT = 720;
    private static final int CAMERA_FPS = 24;
    private static final int OFFER_TIMEOUT_SECONDS = 25;

    private static volatile CameraSessionService instance;
    private static volatile String lastStatus = "Camera is off.";
    private static volatile boolean rtcLibraryInitialized;
    private static volatile SurfaceViewRenderer requestedLocalRenderer;
    private static volatile SurfaceViewRenderer requestedRemoteRenderer;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PocketBridge-camera");
        thread.setDaemon(true);
        return thread;
    });
    private final android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
    private volatile boolean stopping;
    private volatile boolean initializing;
    private volatile boolean initialized;
    private volatile boolean failed;
    private volatile String status = "Starting the phone camera…";
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private SurfaceTextureHelper textureHelper;
    private CameraVideoCapturer capturer;
    private VideoSource videoSource;
    private VideoTrack localTrack;
    private volatile VideoTrack remoteTrack;
    private volatile PeerConnection peer;
    private volatile String pendingAnswer;
    private volatile CountDownLatch answerLatch;
    private volatile boolean iceComplete;
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    private boolean localRendererReady;
    private boolean remoteRendererReady;
    private boolean localSinkAttached;
    private boolean remoteSinkAttached;

    public static boolean isRunning() {
        CameraSessionService service = instance;
        return service != null && service.initialized && service.localTrack != null;
    }

    public static boolean isStarting() {
        CameraSessionService service = instance;
        return service != null && service.initializing;
    }

    public static String statusText() {
        CameraSessionService service = instance;
        return service == null ? lastStatus : service.status;
    }

    /** The activity owns the renderer views; this service owns the camera and EGL context. */
    public static void attachRenderers(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        requestedLocalRenderer = local;
        requestedRemoteRenderer = remote;
        CameraSessionService service = instance;
        if (service != null) service.mainHandler.post(() -> service.attachRenderersOnMain(local, remote));
    }

    public static void detachRenderers(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        if (requestedLocalRenderer == local) requestedLocalRenderer = null;
        if (requestedRemoteRenderer == remote) requestedRemoteRenderer = null;
        CameraSessionService service = instance;
        if (service != null) service.mainHandler.post(() -> service.detachRenderersOnMain(local, remote));
    }

    /** Called by the authenticated phone server after the person has started camera sharing. */
    static String answerOffer(String sdp) {
        CameraSessionService service = instance;
        if (service == null || !service.initialized || sdp == null || sdp.isEmpty()) {
            lastStatus = service == null ? "Start the phone camera session first." : service.status;
            return null;
        }
        return service.acceptOffer(sdp);
    }

    static String lastOfferError() {
        CameraSessionService service = instance;
        return service == null ? lastStatus : service.status;
    }

    static void stopPeer() {
        CameraSessionService service = instance;
        if (service != null) service.mainHandler.post(service::stopSelf);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (initialized || initializing) return START_NOT_STICKY;
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                failed = true;
                status = lastStatus = "Allow camera access in PocketBridge, then start the session again.";
                stopSelf();
                return START_NOT_STICKY;
            }
            failed = false;
            status = lastStatus = "Starting the phone camera…";
            initializing = true;
            worker.execute(this::initializeCamera);
        } catch (Exception error) {
            failed = true;
            status = lastStatus = "Could not start the camera: " + safeMessage(error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void initializeCamera() {
        try {
            if (!rtcLibraryInitialized) {
                synchronized (CameraSessionService.class) {
                    if (!rtcLibraryInitialized) {
                        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
                        rtcLibraryInitialized = true;
                    }
                }
            }
            eglBase = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setOptions(new PeerConnectionFactory.Options())
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();

            Camera2Enumerator cameras = new Camera2Enumerator(getApplicationContext());
            String chosen = null;
            for (String name : cameras.getDeviceNames()) {
                if (cameras.isFrontFacing(name)) { chosen = name; break; }
                if (chosen == null) chosen = name;
            }
            if (chosen == null) throw new IllegalStateException("This phone does not report a usable camera.");
            VideoCapturer newCapturer = cameras.createCapturer(chosen, null);
            if (!(newCapturer instanceof CameraVideoCapturer)) throw new IllegalStateException("Android could not open the phone camera.");
            capturer = (CameraVideoCapturer) newCapturer;
            videoSource = factory.createVideoSource(false);
            textureHelper = SurfaceTextureHelper.create("PocketBridge-camera", eglBase.getEglBaseContext());
            capturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            capturer.startCapture(CAMERA_WIDTH, CAMERA_HEIGHT, CAMERA_FPS);
            localTrack = factory.createVideoTrack("pocketbridge-camera", videoSource);
            localTrack.setEnabled(true);
            initialized = true;
            initializing = false;
            status = lastStatus = "Phone camera ready. Connect from the Edge Camera page and approve the laptop camera there too.";
            mainHandler.post(() -> attachRenderersOnMain(requestedLocalRenderer, requestedRemoteRenderer));
        } catch (Exception error) {
            initializing = false;
            if (!stopping) {
                failed = true;
                status = lastStatus = "Could not open the phone camera: " + safeMessage(error);
                android.util.Log.e("PocketBridge", status, error);
            }
            stopSelf();
        }
    }

    private void attachRenderersOnMain(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        if (stopping || eglBase == null) return;
        if (localRenderer != local) {
            if (localSinkAttached && localTrack != null && localRenderer != null) localTrack.removeSink(localRenderer);
            releaseRenderer(localRenderer);
            localRenderer = local;
            localRendererReady = false;
            localSinkAttached = false;
        }
        if (remoteRenderer != remote) {
            if (remoteSinkAttached && remoteTrack != null && remoteRenderer != null) remoteTrack.removeSink(remoteRenderer);
            releaseRenderer(remoteRenderer);
            remoteRenderer = remote;
            remoteRendererReady = false;
            remoteSinkAttached = false;
        }
        if (localRenderer != null) {
            if (!localRendererReady) {
                localRenderer.init(eglBase.getEglBaseContext(), null);
                localRenderer.setEnableHardwareScaler(true);
                localRenderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
                localRendererReady = true;
            }
            if (localTrack != null && !localSinkAttached) {
                localTrack.addSink(localRenderer);
                localSinkAttached = true;
            }
        }
        if (remoteRenderer != null) {
            if (!remoteRendererReady) {
                remoteRenderer.init(eglBase.getEglBaseContext(), null);
                remoteRenderer.setEnableHardwareScaler(true);
                remoteRenderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
                remoteRendererReady = true;
            }
            if (remoteTrack != null && !remoteSinkAttached) {
                remoteTrack.addSink(remoteRenderer);
                remoteSinkAttached = true;
            }
        }
    }

    private void detachRenderersOnMain(SurfaceViewRenderer local, SurfaceViewRenderer remote) {
        if (localSinkAttached && localTrack != null && local != null) localTrack.removeSink(local);
        if (remoteSinkAttached && remoteTrack != null && remote != null) remoteTrack.removeSink(remote);
        if (localRenderer == local) {
            localRenderer = null; localRendererReady = false; localSinkAttached = false;
            releaseRenderer(local);
        }
        if (remoteRenderer == remote) {
            remoteRenderer = null; remoteRendererReady = false; remoteSinkAttached = false;
            releaseRenderer(remote);
        }
    }

    private String acceptOffer(String offerSdp) {
        synchronized (this) {
            closePeer();
            status = lastStatus = "Connecting both cameras over the local hotspot…";
            pendingAnswer = null;
            iceComplete = false;
            CountDownLatch latch = new CountDownLatch(1);
            answerLatch = latch;
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            PeerConnection active = factory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                        status = lastStatus = "Both cameras are connected directly over the hotspot.";
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        status = lastStatus = "Camera connection failed. Keep both devices on this hotspot and reconnect.";
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        status = lastStatus = "Camera link interrupted. Waiting for the hotspot connection…";
                    }
                }
                @Override public void onIceConnectionReceivingChange(boolean receiving) { }
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) { iceComplete = true; finishAnswer(); }
                }
                @Override public void onIceCandidate(IceCandidate candidate) { }
                @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
                @Override public void onAddStream(org.webrtc.MediaStream stream) { }
                @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
                @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
                @Override public void onRenegotiationNeeded() { }
                @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) {
                    if (receiver.track() instanceof VideoTrack) {
                        VideoTrack incoming = (VideoTrack) receiver.track();
                        incoming.setEnabled(true);
                        remoteTrack = incoming;
                        mainHandler.post(() -> attachRenderersOnMain(requestedLocalRenderer, requestedRemoteRenderer));
                    }
                }
            });
            peer = active;
            if (active == null) {
                answerLatch = null;
                status = lastStatus = "The phone could not create a WebRTC camera connection.";
                return null;
            }
            RtpSender sender = active.addTrack(localTrack, Collections.singletonList("pocketbridge-camera"));
            tuneCameraSender(sender);
            SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
            active.setRemoteDescription(new SdpObserverAdapter() {
                @Override public void onSetSuccess() {
                    active.createAnswer(new SdpObserverAdapter() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            active.setLocalDescription(new SdpObserverAdapter() {
                                @Override public void onSetSuccess() { finishAnswer(); }
                                @Override public void onSetFailure(String error) { failAnswer("Could not prepare the phone camera answer: " + error); }
                            }, answer);
                        }
                        @Override public void onCreateFailure(String error) { failAnswer("Could not create the phone camera answer: " + error); }
                    }, new MediaConstraints());
                }
                @Override public void onSetFailure(String error) { failAnswer("Could not read the laptop camera offer: " + error); }
            }, offer);
            try {
                if (!latch.await(OFFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    failAnswer("Camera setup timed out. Keep the Edge Camera page open and try again.");
                    closePeer();
                    return null;
                }
                return pendingAnswer;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failAnswer("Camera setup was interrupted. Start the session again.");
                return null;
            } finally {
                answerLatch = null;
            }
        }
    }

    private void tuneCameraSender(RtpSender sender) {
        if (sender == null) return;
        try {
            RtpParameters parameters = sender.getParameters();
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED;
            for (RtpParameters.Encoding encoding : parameters.encodings) {
                encoding.maxBitrateBps = 3_000_000;
                encoding.minBitrateBps = 200_000;
                encoding.maxFramerate = 24;
            }
            sender.setParameters(parameters);
        } catch (Exception error) {
            android.util.Log.w("PocketBridge", "Could not tune the camera video sender", error);
        }
    }

    private void finishAnswer() {
        if (!iceComplete || peer == null || answerLatch == null) return;
        SessionDescription local = peer.getLocalDescription();
        if (local == null) return;
        pendingAnswer = local.description;
        answerLatch.countDown();
    }

    private void failAnswer(String message) {
        status = lastStatus = message;
        pendingAnswer = "";
        CountDownLatch latch = answerLatch;
        if (latch != null) latch.countDown();
    }

    private synchronized void closePeer() {
        PeerConnection old = peer;
        peer = null;
        VideoTrack oldRemoteTrack = remoteTrack;
        remoteTrack = null;
        if (remoteSinkAttached && oldRemoteTrack != null && remoteRenderer != null) oldRemoteTrack.removeSink(remoteRenderer);
        remoteSinkAttached = false;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) { }
            try { old.dispose(); } catch (Exception ignored) { }
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        initialized = false;
        initializing = false;
        if (!failed) status = lastStatus = "Camera is off.";
        try { worker.execute(this::releaseCameraResources); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
        worker.shutdown();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void releaseCameraResources() {
        closePeer();
        if (localSinkAttached && localTrack != null && localRenderer != null) {
            try { localTrack.removeSink(localRenderer); } catch (Exception ignored) { }
            localSinkAttached = false;
        }
        try { if (capturer != null) capturer.stopCapture(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { }
        try { if (capturer != null) capturer.dispose(); } catch (Exception ignored) { }
        try { if (localTrack != null) localTrack.dispose(); } catch (Exception ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) { }
        try { if (textureHelper != null) textureHelper.dispose(); } catch (Exception ignored) { }
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) { }
        releaseRenderer(localRenderer);
        releaseRenderer(remoteRenderer);
        localRenderer = null;
        remoteRenderer = null;
        localRendererReady = false;
        remoteRendererReady = false;
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) { }
    }

    private void releaseRenderer(SurfaceViewRenderer renderer) {
        if (renderer == null) return;
        try { renderer.release(); } catch (Exception error) {
            android.util.Log.w("PocketBridge", "Could not release a camera preview", error);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PocketBridge camera", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows while your camera is shared with a nearby device.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, CameraSessionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("PocketBridge camera is active")
                .setContentText("Your camera is available to the approved local camera session.")
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop camera", stop)
                .setOngoing(true).build();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
