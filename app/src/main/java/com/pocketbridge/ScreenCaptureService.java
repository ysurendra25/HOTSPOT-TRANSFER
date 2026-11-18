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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;

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
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
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
    private volatile JpegSink jpegSink;
    private int captureWidth;
    private int captureHeight;

    static final class JpegFrame {
        final long sequence;
        final byte[] bytes;
        JpegFrame(long sequence, byte[] bytes) { this.sequence = sequence; this.bytes = bytes; }
    }

    public static boolean isRunning() { return instance != null && instance.videoTrack != null; }

    public static String getSetupError() { return lastSetupError; }

    public static String getIceState() { return lastIceState; }

    public static String getLastOfferError() { return lastOfferError; }

    static JpegFrame awaitJpegFrame(long afterSequence, long timeoutMs) {
        ScreenCaptureService service = instance;
        JpegSink sink = service == null ? null : service.jpegSink;
        return sink == null ? null : sink.awaitAfter(afterSequence, timeoutMs);
    }

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

    /** JPEG is CPU-heavy, so reserve it for old browsers or a failed WebRTC link. */
    private synchronized void enableJpegFallback() {
        if (videoTrack == null || jpegSink != null) return;
        jpegSink = new JpegSink();
        videoTrack.addSink(jpegSink);
    }

    private synchronized void disableJpegFallback() {
        if (jpegSink == null) return;
        if (videoTrack != null) {
            try { videoTrack.removeSink(jpegSink); } catch (Exception ignored) { }
        }
        jpegSink = null;
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
                    // Keep one MediaProjection capture session alive. Restarting it to
                    // refresh a still frame breaks Android 14+ one-session capture rules.
                    disableJpegFallback();
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

    /** Encodes a modest, crisp browser stream without ICE or an internet relay. */
    private static final class JpegSink implements VideoSink {
        private static final int MAX_EDGE = 1280;
        private static final long MIN_FRAME_NS = 66_000_000L;
        private final Object frameLock = new Object();
        private volatile JpegFrame latest;
        private long sequence;
        private long lastFrameNs;
        private volatile long lastFrameAtMs;

        @Override public synchronized void onFrame(VideoFrame frame) {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            if (now - lastFrameNs < MIN_FRAME_NS) return;
            lastFrameNs = now;
            byte[] jpeg = encode(frame);
            if (jpeg == null) return;
            synchronized (frameLock) {
                latest = new JpegFrame(++sequence, jpeg);
                lastFrameAtMs = android.os.SystemClock.elapsedRealtime();
                frameLock.notifyAll();
            }
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
            float scale = Math.min(1f, (float) MAX_EDGE / Math.max(sourceWidth, sourceHeight));
            int width = Math.max(2, ((int) (sourceWidth * scale) / 2) * 2);
            int height = Math.max(2, ((int) (sourceHeight * scale) / 2) * 2);
            VideoFrame.Buffer scaled = width != sourceWidth || height != sourceHeight
                    ? source.cropAndScale(0, 0, sourceWidth, sourceHeight, width, height)
                    : source;
            VideoFrame.I420Buffer i420 = null;
            try {
                i420 = scaled.toI420();
                if (i420 == null) return null;
                byte[] nv21 = toNv21(i420, width, height);
                if (nv21 == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        .compressToJpeg(new Rect(0, 0, width, height), 78, out)) return null;
                byte[] data = out.toByteArray();
                if (frame.getRotation() == 0) return data;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return data;
                Matrix matrix = new Matrix();
                matrix.postRotate(frame.getRotation());
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                ByteArrayOutputStream rotatedOut = new ByteArrayOutputStream();
                rotated.compress(Bitmap.CompressFormat.JPEG, 88, rotatedOut);
                rotated.recycle();
                return rotatedOut.toByteArray();
            } catch (Exception | OutOfMemoryError error) {
                android.util.Log.w("PocketBridge", "Could not encode a local screen frame", error);
                return null;
            } finally {
                if (i420 != null) i420.release();
                if (scaled != source) scaled.release();
            }
        }

        private byte[] toNv21(VideoFrame.I420Buffer frame, int width, int height) {
            int ySize = width * height;
            byte[] nv21 = new byte[ySize + ySize / 2];
            ByteBuffer y = frame.getDataY().duplicate();
            ByteBuffer u = frame.getDataU().duplicate();
            ByteBuffer v = frame.getDataV().duplicate();
            int out = 0;
            for (int row = 0; row < height; row++) {
                int base = row * frame.getStrideY();
                for (int col = 0; col < width; col++) nv21[out++] = y.get(base + col);
            }
            for (int row = 0; row < height / 2; row++) {
                int uBase = row * frame.getStrideU();
                int vBase = row * frame.getStrideV();
                for (int col = 0; col < width / 2; col++) {
                    nv21[out++] = v.get(vBase + col);
                    nv21[out++] = u.get(uBase + col);
                }
            }
            return nv21;
        }
    }

    @Override public void onDestroy() {
        closePeer();
        if (jpegSink != null && videoTrack != null) {
            try { videoTrack.removeSink(jpegSink); } catch (Exception ignored) { }
            jpegSink = null;
        }
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
