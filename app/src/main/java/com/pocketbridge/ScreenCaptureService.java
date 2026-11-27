package com.pocketbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.HardwareVideoEncoderFactory;
import org.webrtc.VideoCodecInfo;
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
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private String viewerId = "";
    private volatile RtpSender sender;
    private volatile String pendingAnswer;
    private volatile CountDownLatch answerLatch;
    private volatile boolean iceGatheringComplete;
    private volatile JpegSink jpegSink;
    private volatile int captureWidth;
    private volatile int captureHeight;
    private volatile ScreenEncoderPlan encoderPlan;
    private final HandlerThread captureThread = new HandlerThread("PocketBridge-screen-setup");
    private Handler captureHandler;
    private volatile boolean initializing;
    private volatile boolean stopping;

    static final class JpegFrame {
        final long sequence;
        final byte[] bytes;
        JpegFrame(long sequence, byte[] bytes) { this.sequence = sequence; this.bytes = bytes; }
    }

    public static boolean isRunning() {
        ScreenCaptureService service = instance;
        return service != null && !service.stopping && service.videoTrack != null;
    }

    public static boolean isStarting() {
        ScreenCaptureService service = instance;
        return service != null && !service.stopping && service.initializing;
    }

    public static String getSetupError() { return lastSetupError; }

    public static String getIceState() { return lastIceState; }

    public static String getLastOfferError() { return lastOfferError; }

    static JpegFrame awaitJpegFrame(long afterSequence, long timeoutMs) {
        ScreenCaptureService service = instance;
        JpegSink sink = service == null ? null : service.jpegSink;
        if (sink == null) {
            // No encoder yet (or it was just switched off): wait a little instead
            // of returning at once. Returning at once turned the server's stream
            // loop into a busy spin that pinned a CPU core and froze the phone.
            try { Thread.sleep(Math.max(1, Math.min(timeoutMs, 100))); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        }
        return sink.awaitAfter(afterSequence, timeoutMs);
    }

    private static final java.util.concurrent.atomic.AtomicInteger MJPEG_VIEWERS = new java.util.concurrent.atomic.AtomicInteger();

    /** A browser opened the JPEG stream: keep the JPEG encoder on while it watches. */
    static synchronized void mjpegViewerStarted() {
        MJPEG_VIEWERS.incrementAndGet();
        prepareMjpegFallback();
    }

    /** The JPEG encoder is CPU-heavy; switch it off when the last JPEG viewer leaves. */
    static synchronized void mjpegViewerEnded() {
        if (MJPEG_VIEWERS.decrementAndGet() <= 0) {
            MJPEG_VIEWERS.set(0);
            ScreenCaptureService service = instance;
            if (service != null) service.disableJpegFallback();
        }
    }

    static int mjpegViewers() { return MJPEG_VIEWERS.get(); }

    /** Enable the JPEG compatibility stream only when a browser needs it. */
    static void prepareMjpegFallback() {
        ScreenCaptureService service = instance;
        if (service != null) service.enableJpegFallback();
    }

    /** How many JPEG frames the phone has produced this session. */
    static long framesSent() {
        ScreenCaptureService service = instance;
        JpegSink sink = service == null ? null : service.jpegSink;
        return sink == null ? 0 : sink.sequence;
    }

    /** Milliseconds since the last screen frame, or -1 when none has arrived yet. */
    static long lastFrameAgeMs() {
        ScreenCaptureService service = instance;
        JpegSink sink = service == null ? null : service.jpegSink;
        if (sink == null || sink.lastFrameAtMs == 0) return -1;
        return android.os.SystemClock.elapsedRealtime() - sink.lastFrameAtMs;
    }

    /** "1080x1920" for the current capture, or an empty string before capture starts. */
    static String captureDescription() {
        ScreenCaptureService service = instance;
        if (service == null || service.captureWidth == 0) return "";
        return service.captureWidth + "x" + service.captureHeight;
    }

    /** "H264 hardware" etc.: the encoder the direct stream will use. */
    static String encoderDescription() {
        ScreenCaptureService service = instance;
        ScreenEncoderPlan plan = service == null ? null : service.encoderPlan;
        return plan == null ? "" : plan.describe();
    }

    public static String answerOffer(String sdp, String viewer) {
        ScreenCaptureService service = instance;
        if (service == null || sdp == null || sdp.isEmpty()) return null;
        synchronized (service.offerLock) {
            if (service.stopping || service.videoTrack == null) return null;
            service.viewerId = viewer;
            try {
                return service.acceptOfferLocked(sdp);
            } catch (RuntimeException error) {
                lastOfferError = "The phone could not prepare this screen connection. Retry Watch screen.";
                android.util.Log.e("PocketBridge", "Screen offer failed", error);
                service.closePeer();
                return null;
            }
        }
    }

    public static void stopPeer(String viewer) {
        ScreenCaptureService service = instance;
        if (service == null) return;
        synchronized (service.offerLock) {
            if (service.viewerId.equals(viewer)) service.closePeer();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        instance = this;
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // Always answer startForegroundService() promptly, even when a capture is
            // already running, or Android can stop the app for not calling it in time.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTIFICATION_ID, buildNotification());
            if (isRunning() || initializing) return START_NOT_STICKY;
            if (intent == null || intent.getIntExtra(EXTRA_RESULT_CODE, 0) == 0) {
                lastSetupError = "Android screen-capture permission was not provided.";
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            Intent permissionData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (permissionData == null) throw new IllegalArgumentException("Android did not return screen-capture permission data.");
            lastSetupError = null;
            initializing = true;
            captureHandler.post(() -> {
                if (stopping) return;
                try {
                    initialize(permissionData);
                } catch (Exception error) {
                    if (!stopping) {
                        lastSetupError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                        android.util.Log.e("PocketBridge", "Screen share setup failed", error);
                        stopSelf();
                    }
                } finally {
                    initializing = false;
                }
            });
        } catch (Exception error) {
            lastSetupError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            android.util.Log.e("PocketBridge", "Screen share setup failed", error);
            initializing = false;
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void initialize(Intent permissionData) throws Exception {
        RtcRuntime.initialize(this);
        eglBase = EglBase.create();
        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        encoderPlan = planEncoder(eglBase);
        android.util.Log.i("PocketBridge", "Screen encoder plan: " + encoderPlan.describe() + " order=" + encoderPlan.codecOrder);
        // Marking the source as a screencast avoids needless work on a static screen.
        // The sender uses balanced adaptation so congestion does not build a stale
        // multi-second frame queue on a busy phone hotspot.
        videoSource = factory.createVideoSource(true);
        surfaceTextureHelper = SurfaceTextureHelper.create("PocketBridge-screen", eglBase.getEglBaseContext());
        capturer = new ScreenCapturerAndroid(permissionData, new android.media.projection.MediaProjection.Callback() {
            @Override public void onStop() {
                android.util.Log.i("PocketBridge", "Screen capture stopped by Android");
                stopSelf();
            }

            @Override public void onCapturedContentResize(int width, int height) {
                captureHandler.post(() -> resizeCaptureForContent(width, height));
            }
        });
        capturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        int[] size = captureSize();
        capturer.startCapture(size[0], size[1], ScreenStreamTuning.MAX_FRAMERATE);
        captureWidth = size[0];
        captureHeight = size[1];
        limitSourceFrameRate(size[0], size[1]);
        VideoTrack track = factory.createVideoTrack("pocketbridge-screen", videoSource);
        track.setEnabled(true);
        videoTrack = track;
        // From API 34 the captured app can be smaller than the device display.
        if (Build.VERSION.SDK_INT < 34) watchRotation();
    }

    private android.hardware.display.DisplayManager.DisplayListener rotationListener;

    /**
     * The virtual display is created at the size the phone had when sharing
     * started. Before Android 14 nothing tells the capturer that the phone
     * rotated, so a landscape screen was squeezed into the portrait-sized
     * capture. Follow the real display size on every rotation instead.
     */
    private void watchRotation() {
        if (stopping) return;
        android.hardware.display.DisplayManager displays = getSystemService(android.hardware.display.DisplayManager.class);
        if (displays == null) return;
        rotationListener = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) { }
            @Override public void onDisplayRemoved(int displayId) { }
            @Override public void onDisplayChanged(int displayId) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return;
                int[] size = captureSize();
                resizeCaptureForContent(size[0], size[1]);
            }
        };
        displays.registerDisplayListener(rotationListener, captureHandler);
    }

    /** Keep the native display size for the direct WebRTC stream. The JPEG fallback scales separately. */
    private int[] captureSize() {
        android.hardware.display.DisplayManager displays = getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display display = displays.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        if (display != null) display.getRealMetrics(metrics);
        else metrics.setTo(getResources().getDisplayMetrics());
        return boundedCaptureSize(metrics.widthPixels, metrics.heightPixels);
    }

    private void resizeCaptureForContent(int contentWidth, int contentHeight) {
        if (stopping || contentWidth <= 0 || contentHeight <= 0 || capturer == null || videoSource == null) return;
        int[] size = boundedCaptureSize(contentWidth, contentHeight);
        if (size[0] == captureWidth && size[1] == captureHeight) return;
        try {
            capturer.changeCaptureFormat(size[0], size[1], ScreenStreamTuning.MAX_FRAMERATE);
            captureWidth = size[0];
            captureHeight = size[1];
            limitSourceFrameRate(size[0], size[1]);
            android.util.Log.i("PocketBridge", "Screen capture resized to " + captureWidth + "x" + captureHeight);
        } catch (RuntimeException error) {
            android.util.Log.e("PocketBridge", "Could not resize screen capture after rotation", error);
        }
    }

    private int[] boundedCaptureSize(int width, int height) {
        // Keep the display's original pixel size whenever the chosen hardware
        // encoder accepts it. A portrait 1080x2400 frame is larger than what
        // many encoders take (often 1920x1088); feeding it anyway produced a
        // corrupted or half picture, or a silent fall back to software VP8.
        ScreenEncoderPlan plan = encoderPlan;
        return plan == null ? ScreenCaptureSizing.evenSize(width, height) : plan.captureSize(width, height);
    }

    /**
     * A virtual display delivers frames at the panel's refresh rate (60–120 Hz).
     * Cap the source at the stream's frame rate without changing its size, so
     * the encoder and the JPEG fallback are not handed two to four frames for
     * every one that is sent.
     */
    private void limitSourceFrameRate(int width, int height) {
        if (videoSource == null) return;
        // UNDEFINED aspect ratios and no pixel limit: only the frame rate is
        // limited. A fixed size here would make WebRTC crop frames of another
        // shape, which is exactly the half-picture bug after a rotation.
        try {
            videoSource.adaptOutputFormat(VideoSource.AspectRatio.UNDEFINED, null,
                    VideoSource.AspectRatio.UNDEFINED, null, ScreenStreamTuning.MAX_FRAMERATE);
        } catch (RuntimeException ignored) { }
    }

    /** Which codecs WebRTC runs in hardware on this phone, and what sizes they accept. */
    private static ScreenEncoderPlan planEncoder(EglBase egl) {
        java.util.Set<String> hardware = new java.util.HashSet<>();
        try {
            for (VideoCodecInfo codec : new HardwareVideoEncoderFactory(egl.getEglBaseContext(), true, true).getSupportedCodecs()) {
                hardware.add(codec.name.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (RuntimeException ignored) { }
        return ScreenEncoderPlan.choose(HardwareEncoders.usableByWebRtc(HardwareEncoders.list(), hardware));
    }

    /** JPEG is CPU-heavy, so reserve it for old browsers or a failed WebRTC link. */
    private synchronized void enableJpegFallback() {
        if (stopping || videoTrack == null || jpegSink != null) return;
        jpegSink = new JpegSink();
        videoTrack.addSink(jpegSink);
        // A still Android display may never deliver a new texture after the sink joins.
        surfaceTextureHelper.forceFrame();
    }

    private synchronized void disableJpegFallback() {
        if (jpegSink == null) return;
        JpegSink sink = jpegSink;
        if (videoTrack != null) {
            try { videoTrack.removeSink(sink); } catch (Exception ignored) { }
        }
        jpegSink = null;
        sink.close();
    }

    private final Object offerLock = new Object();

    private String acceptOfferLocked(String offerSdp) {
        closePeer();
        lastOfferError = null;
        pendingAnswer = null;
        iceGatheringComplete = false;
        lastIceState = "new";
        CountDownLatch latch = new CountDownLatch(1);
        answerLatch = latch;
        if (stopping) {
            answerLatch = null;
            return null;
        }
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection activePeer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                lastIceState = state == null ? "unknown" : state.toString().toLowerCase(java.util.Locale.ROOT);
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    captureHandler.post(() -> {
                        if (stopping) return;
                        surfaceTextureHelper.forceFrame();
                    });
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
                // Adapt both resolution and frame rate to current hotspot capacity.
                parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED;
                for (RtpParameters.Encoding encoding : parameters.encodings) {
                    encoding.maxBitrateBps = ScreenStreamTuning.MAX_KBPS * 1000;
                    encoding.minBitrateBps = ScreenStreamTuning.MIN_KBPS * 1000;
                    encoding.maxFramerate = ScreenStreamTuning.MAX_FRAMERATE;
                    encoding.networkPriority = 3; // Priority.HIGH
                    encoding.bitratePriority = 4.0;
                }
                sender.setParameters(parameters);
            } catch (Exception ignored) { }
        }
        // Put the hardware codec first: libwebrtc answers in the offer's order,
        // so this decides what the phone encodes with (see ScreenEncoderPlan).
        ScreenEncoderPlan plan = encoderPlan;
        String preferred = plan == null ? offerSdp : ScreenEncoderPlan.preferCodecs(offerSdp, plan.codecOrder);
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, ScreenStreamTuning.tuneOffer(preferred));
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
            if (!latch.await(20, TimeUnit.SECONDS)) {
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
        PeerConnection closing;
        synchronized (this) {
            closing = peer;
            peer = null;
            sender = null;
        }
        if (closing != null) {
            try { closing.close(); } catch (Exception ignored) { }
            try { closing.dispose(); } catch (Exception ignored) { }
        }
    }

    /** Encodes the local compatibility stream when direct WebRTC cannot be used. */
    private static final class JpegSink implements VideoSink {
        // The WebRTC path keeps the source resolution. Compatibility JPEG is a
        // last-resort path, so cap its CPU and hotspot bandwidth to prevent a
        // growing encode/send queue from turning into seconds of stale video.
        // Native resolution: the fallback is for browsers without WebRTC and
        // should look like the phone, not like a thumbnail of it. 4096 only
        // guards memory on unusual displays.
        // 0.7.0.1: the fallback had been raised to native size at quality 88.
        // Each frame then needed a full-resolution GPU read-back on the same
        // thread that delivers frames to the WebRTC encoder, so the two paths
        // slowed each other down while both ran. Scale on the GPU first.
        private static final int MAX_EDGE = 1920;
        static final int QUALITY = 80;
        // At most 20 fps. Frames arriving while one is still encoding are
        // dropped, so only the newest frame is ever sent.
        private static final long MIN_FRAME_NS = 50_000_000L;
        private final Object frameLock = new Object();
        private final AtomicBoolean encoding = new AtomicBoolean();
        private final ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "PocketBridge-JPEG");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setDaemon(true);
            return thread;
        });
        private volatile JpegFrame latest;
        private long sequence;
        private long lastFrameNs;
        private volatile long lastFrameAtMs;

        @Override public void onFrame(VideoFrame frame) {
            // Convert at most one frame at a time on a worker. If conversion is
            // still busy, drop this frame instead of blocking WebRTC's capture
            // thread or building a queue of stale frames.
            if (!encoding.compareAndSet(false, true)) return;
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            if (now - lastFrameNs < MIN_FRAME_NS) {
                encoding.set(false);
                return;
            }
            lastFrameNs = now;
            try {
                frame.retain();
            } catch (RuntimeException error) {
                encoding.set(false);
                android.util.Log.w("PocketBridge", "Could not retain a screen frame for JPEG fallback", error);
                return;
            }
            try {
                encoder.execute(() -> {
                    try {
                        byte[] jpeg = encode(frame);
                        if (jpeg != null) publish(jpeg);
                    } finally {
                        try { frame.release(); }
                        finally { encoding.set(false); }
                    }
                });
            } catch (RejectedExecutionException rejected) {
                frame.release();
                encoding.set(false);
            }
        }

        private void publish(byte[] jpeg) {
            synchronized (frameLock) {
                latest = new JpegFrame(++sequence, jpeg);
                lastFrameAtMs = android.os.SystemClock.elapsedRealtime();
                frameLock.notifyAll();
            }
        }

        void close() {
            encoder.shutdown();
            synchronized (frameLock) { frameLock.notifyAll(); }
        }

        JpegFrame awaitAfter(long afterSequence, long timeoutMs) {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (frameLock) {
                while (latest == null || latest.sequence <= afterSequence) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) return null;
                    try { frameLock.wait(remaining); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
                }
                return latest;
            }
        }

        private byte[] encode(VideoFrame frame) {
            VideoFrame.Buffer source = frame.getBuffer();
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            int[] targetSize = ScreenCaptureSizing.fitWithinMaxEdge(sourceWidth, sourceHeight, MAX_EDGE);
            int width = targetSize[0];
            int height = targetSize[1];
            VideoFrame.Buffer scaled = width != sourceWidth || height != sourceHeight
                    ? source.cropAndScale(0, 0, sourceWidth, sourceHeight, width, height)
                    : source;
            VideoFrame.I420Buffer i420 = null;
            try {
                i420 = scaled.toI420();
                if (i420 == null) return null;
                byte[] nv21 = toNv21(i420, width, height);
                if (nv21 == null) return null;
                Nv21Rotation.Frame rotated = Nv21Rotation.rotate(nv21, width, height, frame.getRotation());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!new YuvImage(rotated.data, ImageFormat.NV21, rotated.width, rotated.height, null)
                        .compressToJpeg(new Rect(0, 0, rotated.width, rotated.height), QUALITY, out)) return null;
                return out.toByteArray();
            } catch (Exception | OutOfMemoryError error) {
                android.util.Log.w("PocketBridge", "Could not encode a local screen frame", error);
                return null;
            } finally {
                if (i420 != null) i420.release();
                if (scaled != source) scaled.release();
            }
        }

        private byte[] toNv21(VideoFrame.I420Buffer frame, int width, int height) {
            return I420Packing.toNv21(frame.getDataY(), frame.getStrideY(), frame.getDataU(), frame.getStrideU(),
                    frame.getDataV(), frame.getStrideV(), width, height);
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        initializing = false;
        CountDownLatch negotiating = answerLatch;
        if (negotiating != null) negotiating.countDown();
        captureHandler.post(() -> {
            try { releaseCaptureResources(); }
            finally { captureThread.quitSafely(); }
        });
        if (instance == this) instance = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }

    private void releaseCaptureResources() {
        if (rotationListener != null) {
            android.hardware.display.DisplayManager displays = getSystemService(android.hardware.display.DisplayManager.class);
            if (displays != null) displays.unregisterDisplayListener(rotationListener);
            rotationListener = null;
        }
        synchronized (offerLock) { closePeer(); }
        disableJpegFallback();
        try { if (capturer != null) { capturer.stopCapture(); capturer.dispose(); } } catch (Exception ignored) { }
        try { if (videoTrack != null) videoTrack.dispose(); } catch (Exception ignored) { }
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) { }
        try { if (surfaceTextureHelper != null) surfaceTextureHelper.dispose(); } catch (Exception ignored) { }
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) { }
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) { }
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
