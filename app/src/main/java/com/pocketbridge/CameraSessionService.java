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
 * Direct WebRTC camera call over the local link. The foreground service starts
 * when this phone's user consents (asks for a call, or accepts one) from the
 * app, so Android allows the camera later even if the app goes to the
 * background. The camera itself opens only once BOTH sides have accepted:
 * {@link CameraCall} on this phone's server (mode "host": a laptop or phone
 * calls in and this phone answers the offer) or {@link PeerCameraLink} for a
 * call to another phone's server (mode "peer": this phone sends the offer).
 * Either side stopping ends the service. No STUN/TURN servers are configured;
 * only UDP host candidates are used.
 */
public final class CameraSessionService extends Service {
    static final String ACTION_STOP = "com.pocketbridge.action.STOP_CAMERA";
    static final String ACTION_SWITCH = "com.pocketbridge.action.SWITCH_CAMERA";
    static final String EXTRA_MODE = "mode";
    static final String MODE_HOST = "host";
    static final String MODE_PEER = "peer";
    private static final int READY_WAIT_SECONDS = 15;
    private static final String CHANNEL_ID = "pocketbridge_camera";
    private static final int NOTIFICATION_ID = 1203;
    private static final int CAMERA_WIDTH = 1280;
    private static final int CAMERA_HEIGHT = 720;
    private static final int CAMERA_FPS = 24;
    private static final int OFFER_TIMEOUT_SECONDS = 25;

    private static volatile CameraSessionService instance;
    private static volatile String lastStatus = "Camera is off.";
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
    private final CountDownLatch firstCameraFrame = new CountDownLatch(1);
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
    private volatile String mode = MODE_HOST;
    private volatile String callId = "";
    private volatile boolean offerSent;
    private volatile boolean frontCamera = true;
    private volatile boolean live;
    private ScreenEncoderPlan encoderPlan;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CameraCall.Listener callListener = call -> mainHandler.post(this::evaluate);
    private final PeerCameraLink.Listener peerListener = link -> mainHandler.post(this::evaluate);
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

    /** The service exists: consent was given on this phone and the call has not ended. */
    static boolean isActive() { return instance != null; }

    /** Video flows over an ICE-connected link. */
    static boolean isLive() {
        CameraSessionService service = instance;
        return service != null && service.live;
    }

    static String mode() {
        CameraSessionService service = instance;
        return service == null ? "" : service.mode;
    }

    static boolean isFrontCamera() {
        CameraSessionService service = instance;
        return service == null || service.frontCamera;
    }

    /** Front ↔ back. Safe to call from any thread; ignored before the camera opens. */
    static void switchCamera() {
        CameraSessionService service = instance;
        if (service != null) service.worker.execute(service::switchCameraNow);
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

    /**
     * Called by the authenticated phone server for an accepted call. The phone's
     * camera may still be opening (it opens only after both sides accept), so this
     * waits for it instead of refusing the offer: refusing was the old
     * signaling-order failure when the laptop was faster than the phone.
     */
    static String answerOffer(String id, String sdp) {
        if (sdp == null || sdp.isEmpty()) return null;
        long deadline = android.os.SystemClock.elapsedRealtime() + READY_WAIT_SECONDS * 1000L;
        CameraSessionService service = instance;
        while (true) {
            if (service != null && service.initialized) break;
            if (service != null && (service.failed || service.stopping)) { lastStatus = service.status; return null; }
            if (!CameraCall.INSTANCE.mediaAllowed(id)) { lastStatus = "The camera call ended before the phone camera opened."; return null; }
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) {
                lastStatus = service == null
                        ? "Accept the call in PocketBridge on the phone and allow its camera."
                        : "The phone camera did not open in time. Close other camera apps and try again.";
                return null;
            }
            try {
                if (service != null) service.ready.await(Math.min(left, 250), TimeUnit.MILLISECONDS);
                else Thread.sleep(Math.min(left, 250));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
            service = instance;
        }
        if (!MODE_HOST.equals(service.mode)) { lastStatus = "This phone's camera is busy in a call with another phone."; return null; }
        service.callId = id;
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
            if (intent != null && ACTION_SWITCH.equals(intent.getAction())) {
                worker.execute(this::switchCameraNow);
                return START_NOT_STICKY;
            }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                failed = true;
                status = lastStatus = "Allow camera access in PocketBridge, then start the call again.";
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent != null && intent.getStringExtra(EXTRA_MODE) != null && !initialized && !initializing) {
                mode = MODE_PEER.equals(intent.getStringExtra(EXTRA_MODE)) ? MODE_PEER : MODE_HOST;
            }
            failed = false;
            CameraCall.INSTANCE.addListener(callListener);
            PeerCameraLink.INSTANCE.addListener(peerListener);
            evaluate();
        } catch (Exception error) {
            failed = true;
            status = lastStatus = "Could not start the camera: " + safeMessage(error);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Follows the call state: open the camera once both sides accepted, stop when the call ends. */
    private void evaluate() {
        if (stopping) return;
        boolean allowed;
        boolean pending;
        if (MODE_PEER.equals(mode)) {
            PeerCameraLink link = PeerCameraLink.INSTANCE;
            allowed = link.connected() && link.mediaAllowed();
            pending = link.connected() && "requested".equals(link.state());
            if (allowed) callId = link.callId();
        } else {
            CameraCall call = CameraCall.INSTANCE;
            allowed = call.mediaAllowed(call.id());
            pending = call.state() == CameraCall.State.REQUESTED;
            if (allowed) callId = call.id();
        }
        if (allowed) {
            if (!initialized && !initializing) {
                status = lastStatus = "Both sides accepted. Opening the phone camera…";
                initializing = true;
                worker.execute(this::initializeCamera);
            } else if (initialized && MODE_PEER.equals(mode) && !offerSent) {
                offerSent = true;
                worker.execute(this::callPeer);
            }
        } else if (pending) {
            status = lastStatus = "Waiting for the other side to accept. The camera stays off until both accept.";
        } else {
            String reason = MODE_PEER.equals(mode) ? PeerCameraLink.INSTANCE.status().optString("reason", "")
                    : CameraCall.INSTANCE.reason();
            if (!reason.isEmpty()) status = lastStatus = reason;
            stopSelf();
        }
    }

    private void switchCameraNow() {
        CameraVideoCapturer active = capturer;
        if (active == null || !initialized) return;
        active.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override public void onCameraSwitchDone(boolean isFront) {
                frontCamera = isFront;
                mainHandler.post(() -> { if (localRenderer != null) localRenderer.setMirror(isFront); });
            }
            @Override public void onCameraSwitchError(String error) {
                android.util.Log.w("PocketBridge", "Camera switch failed: " + error);
            }
        });
    }

    private PeerConnection.RTCConfiguration hotspotConfiguration() {
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // Hotspot only: no STUN/TURN, UDP host candidates, gather once.
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        config.candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        return config;
    }

    private void onIceState(PeerConnection.IceConnectionState state) {
        if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
            live = true;
            status = lastStatus = "Live: both cameras are connected directly over the hotspot.";
            if (MODE_HOST.equals(mode)) CameraCall.INSTANCE.markLive(callId);
        } else if (state == PeerConnection.IceConnectionState.FAILED) {
            live = false;
            String reason = "Camera connection failed. Keep both devices on this hotspot and call again.";
            status = lastStatus = reason;
            if (MODE_HOST.equals(mode)) CameraCall.INSTANCE.stop(CameraCall.PHONE, reason);
            else new Thread(PeerCameraLink.INSTANCE::stopCall).start();
            mainHandler.post(this::stopSelf);
        } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            live = false;
            status = lastStatus = "Camera link interrupted. Waiting for the hotspot connection…";
        }
    }

    /** Phone↔phone: this phone offers, the other phone's server answers. */
    private void callPeer() {
        try {
            String id = callId;
            CountDownLatch gathered = new CountDownLatch(1);
            PeerConnection active = factory.createPeerConnection(hotspotConfiguration(), new PeerConnectionObserver() {
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { onIceState(state); }
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown();
                }
                @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) { showRemote(receiver); }
            });
            if (active == null) throw new IllegalStateException("The phone could not create a WebRTC camera connection.");
            synchronized (this) { closePeer(); peer = active; }
            tuneCameraSender(active.addTrack(localTrack, Collections.singletonList("pocketbridge-camera")));
            status = lastStatus = "Calling the other phone's camera…";
            CountDownLatch localSet = new CountDownLatch(1);
            String[] failure = {null};
            active.createOffer(new SdpObserverAdapter() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    String munged = CameraSdp.prepareLocalOffer(offer.description, codecOrder());
                    active.setLocalDescription(new SdpObserverAdapter() {
                        @Override public void onSetSuccess() { localSet.countDown(); }
                        @Override public void onSetFailure(String error) { failure[0] = error; localSet.countDown(); }
                    }, new SessionDescription(SessionDescription.Type.OFFER, munged));
                }
                @Override public void onCreateFailure(String error) { failure[0] = error; localSet.countDown(); }
            }, new MediaConstraints());
            if (!localSet.await(10, TimeUnit.SECONDS) || failure[0] != null) {
                throw new IllegalStateException("Could not prepare the camera offer: " + (failure[0] == null ? "timed out" : failure[0]));
            }
            // Non-trickle signaling: wait for host candidates (fast without STUN), then send once.
            gathered.await(6, TimeUnit.SECONDS);
            SessionDescription local = active.getLocalDescription();
            if (local == null || stopping) return;
            String answer = PeerCameraLink.INSTANCE.offer(id, CameraSdp.hostCandidatesOnly(local.description));
            if (answer.isEmpty()) throw new IllegalStateException("The other phone did not answer the camera call.");
            CountDownLatch remoteSet = new CountDownLatch(1);
            active.setRemoteDescription(new SdpObserverAdapter() {
                @Override public void onSetSuccess() { remoteSet.countDown(); }
                @Override public void onSetFailure(String error) { failure[0] = error; remoteSet.countDown(); }
            }, new SessionDescription(SessionDescription.Type.ANSWER, CameraSdp.hostCandidatesOnly(answer)));
            if (!remoteSet.await(10, TimeUnit.SECONDS) || failure[0] != null) {
                throw new IllegalStateException("Could not read the other phone's answer: " + (failure[0] == null ? "timed out" : failure[0]));
            }
            status = lastStatus = "Connecting both cameras over the local hotspot…";
        } catch (Exception error) {
            if (stopping) return;
            failed = true;
            status = lastStatus = safeMessage(error);
            new Thread(PeerCameraLink.INSTANCE::stopCall).start();
            mainHandler.post(this::stopSelf);
        }
    }

    private void showRemote(org.webrtc.RtpReceiver receiver) {
        if (receiver.track() instanceof VideoTrack) {
            VideoTrack incoming = (VideoTrack) receiver.track();
            incoming.setEnabled(true);
            remoteTrack = incoming;
            mainHandler.post(() -> attachRenderersOnMain(requestedLocalRenderer, requestedRemoteRenderer));
        }
    }

    private java.util.List<String> codecOrder() {
        ScreenEncoderPlan plan = encoderPlan;
        return plan == null ? java.util.Arrays.asList("h264", "vp8", "vp9") : plan.codecOrder;
    }

    private void initializeCamera() {
        try {
            RtcRuntime.initialize(getApplicationContext());
            eglBase = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setOptions(new PeerConnectionFactory.Options())
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();
            encoderPlan = ScreenCaptureService.planEncoder(eglBase);

            Camera2Enumerator cameras = new Camera2Enumerator(getApplicationContext());
            String chosen = null;
            for (String name : cameras.getDeviceNames()) {
                if (cameras.isFrontFacing(name)) { chosen = name; break; }
                if (chosen == null) chosen = name;
            }
            frontCamera = chosen != null && cameras.isFrontFacing(chosen);
            if (chosen == null) throw new IllegalStateException("This phone does not report a usable camera.");
            VideoCapturer newCapturer = cameras.createCapturer(chosen, new CameraVideoCapturer.CameraEventsHandler() {
                @Override public void onCameraError(String message) { captureFailed(message); }
                @Override public void onCameraDisconnected() { captureFailed("Camera disconnected."); }
                @Override public void onCameraFreezed(String message) { captureFailed(message); }
                @Override public void onCameraOpening(String device) { }
                @Override public void onCameraClosed() { }
                @Override public void onFirstFrameAvailable() { firstCameraFrame.countDown(); }
            });
            if (!(newCapturer instanceof CameraVideoCapturer)) throw new IllegalStateException("Android could not open the phone camera.");
            capturer = (CameraVideoCapturer) newCapturer;
            videoSource = factory.createVideoSource(false);
            textureHelper = SurfaceTextureHelper.create("PocketBridge-camera", eglBase.getEglBaseContext());
            capturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            capturer.startCapture(CAMERA_WIDTH, CAMERA_HEIGHT, CAMERA_FPS);
            localTrack = factory.createVideoTrack("pocketbridge-camera", videoSource);
            localTrack.setEnabled(true);
            if (!firstCameraFrame.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No frames arrived. Close other camera apps and try again.");
            }
            if (stopping || failed) return;
            initialized = true;
            initializing = false;
            ready.countDown();
            status = lastStatus = MODE_PEER.equals(mode) ? "Phone camera open. Calling the other phone…"
                    : "Phone camera open. Connecting to the other device…";
            mainHandler.post(() -> {
                attachRenderersOnMain(requestedLocalRenderer, requestedRemoteRenderer);
                evaluate();
            });
        } catch (Exception error) {
            initializing = false;
            if (!stopping && !failed) {
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
                localRenderer.setMirror(frontCamera);
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

    private void captureFailed(String reason) {
        if (stopping) return;
        failed = true;
        status = lastStatus = "Phone camera stopped. " + reason;
        firstCameraFrame.countDown();
        stopSelf();
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
            PeerConnection active = factory.createPeerConnection(hotspotConfiguration(), new PeerConnectionObserver() {
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { onIceState(state); }
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) { iceComplete = true; finishAnswer(); }
                }
                @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) { showRemote(receiver); }
            });
            peer = active;
            if (active == null) {
                answerLatch = null;
                status = lastStatus = "The phone could not create a WebRTC camera connection.";
                return null;
            }
            RtpSender sender = active.addTrack(localTrack, Collections.singletonList("pocketbridge-camera"));
            tuneCameraSender(sender);
            // Hardware H.264 first (libwebrtc answers in the offer's order), camera
            // bitrates, and UDP host candidates only.
            SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER,
                    CameraSdp.prepareRemoteOffer(offerSdp, codecOrder()));
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
                    failAnswer("Camera setup timed out. Keep the camera window open and try again.");
                    closePeer();
                    return null;
                }
                String answer = pendingAnswer;
                return answer == null || answer.isEmpty() ? answer : CameraSdp.hostCandidatesOnly(answer);
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
        live = false;
        CameraCall.INSTANCE.removeListener(callListener);
        PeerCameraLink.INSTANCE.removeListener(peerListener);
        // Either side stopping ends the call for both.
        if (MODE_PEER.equals(mode)) {
            if (PeerCameraLink.INSTANCE.isActive()) new Thread(PeerCameraLink.INSTANCE::stopCall).start();
        } else {
            CameraCall.INSTANCE.stop(CameraCall.PHONE, failed ? status : null);
        }
        firstCameraFrame.countDown();
        CountDownLatch pending = answerLatch;
        if (pending != null) pending.countDown();
        // Release the Activity's views on the main thread before a new service
        // can attach the same views. Hardware teardown remains on the worker.
        detachRenderersOnMain(localRenderer, remoteRenderer);
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
                .setContentTitle("PocketBridge camera call")
                .setContentText("The camera opens only after both sides accept. Tap Stop to end the call for both.")
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop call", stop)
                .setOngoing(true).build();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class PeerConnectionObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
        @Override public void onIceCandidate(IceCandidate candidate) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(org.webrtc.MediaStream stream) { }
        @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }
        @Override public void onDataChannel(org.webrtc.DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) { }
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
