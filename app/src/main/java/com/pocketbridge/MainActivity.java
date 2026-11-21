package com.pocketbridge;

import android.Manifest;
import android.app.Activity;
import android.media.projection.MediaProjectionManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.BaseAdapter;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_SCREEN_CAPTURE = 41;
    private static final int REQUEST_SEND_FILE = 42;
    private static final int REQUEST_QR_SEND_FILE = 43;
    private static final int REQUEST_QR_RECEIVE_SAVE = 45;
    private static final int REQUEST_QR_CAMERA_PERMISSION = 46;
    private static final int REQUEST_SAVE_CHAT_ATTACHMENT = 47;
    private static final int REQUEST_CAMERA_PERMISSION = 48;
    private static final int COLOR_INK = Color.rgb(232, 241, 244);
    private static final int COLOR_MUTED = Color.rgb(157, 178, 187);
    private static final int COLOR_GREEN = Color.rgb(40, 177, 140);
    private static final int COLOR_BG = Color.rgb(10, 18, 23);
    private static final int COLOR_SOFT = Color.rgb(24, 48, 54);
    private static final int COLOR_SURFACE = Color.rgb(17, 31, 37);
    private static final int COLOR_LINE = Color.rgb(43, 63, 71);

    private TextView statusText;
    private TextView networkText;
    private TextView linkText;
    private TextView codeText;
    private TextView modeText;
    private TextView altText;
    private TextView diagText;
    private Button wifiButton;
    private Button hotspotButton;
    private Button stopButton;
    private Button copyButton;
    private Button screenButton;
    private EditText screenHelperLink;
    private EditText libraryHelperLink;
    private EditText laptopFolderPath;
    private EditText laptopFilter;
    private Button[] laptopKindButtons;
    private TextView laptopScreenStatus;
    private ViewGroup laptopScreenParent;
    private ViewGroup.LayoutParams laptopScreenOriginalLayout;
    private int laptopScreenOriginalIndex = -1;
    private android.app.Dialog laptopScreenDialog;
    private EditText qrTextInput;
    private byte[] pendingQrFile;
    private String pendingQrName;
    private String pendingQrMime;
    private final java.util.Map<String, QrAssembly> qrAssemblies = new java.util.HashMap<>();
    private final android.os.Handler qrHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.app.Dialog qrScannerDialog;
    private com.journeyapps.barcodescanner.DecoratedBarcodeView qrScannerView;
    private TextView qrScannerStatus;
    private final java.util.Set<String> seenQrFrames = new java.util.HashSet<>();
    private TextView cameraStatus;
    private Button cameraToggle;
    private org.webrtc.SurfaceViewRenderer phoneCameraPreview;
    private org.webrtc.SurfaceViewRenderer laptopCameraPreview;
    private org.webrtc.EglBase cameraEglBase;
    private android.app.Dialog cameraFullscreenDialog;
    private ViewGroup cameraPreviewParent;
    private ViewGroup.LayoutParams cameraPreviewOriginalLayout;
    private int cameraPreviewOriginalIndex = -1;
    private boolean syncingHelperLinks;
    private Button phoneLibrarySourceButton;
    private Button laptopLibrarySourceButton;
    private boolean receiverRegistered;
    private boolean sharing;

    private static final class QrAssembly {
        final boolean text;
        final String name;
        final String mime;
        final byte[][] chunks;
        int received;
        QrAssembly(QrTransferCodec.Frame frame) {
            text = frame.text;
            name = frame.name;
            mime = frame.mime;
            chunks = new byte[frame.total][];
        }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(ShareSessionService.EXTRA_STATUS);
            String ssid = intent.getStringExtra(ShareSessionService.EXTRA_SSID);
            String password = intent.getStringExtra(ShareSessionService.EXTRA_PASSWORD);
            String url = intent.getStringExtra(ShareSessionService.EXTRA_URL);
            String code = intent.getStringExtra(ShareSessionService.EXTRA_CODE);
            String mode = intent.getStringExtra(ShareSessionService.EXTRA_MODE);
            String alternates = intent.getStringExtra(ShareSessionService.EXTRA_ALT);
            String diagnostics = intent.getStringExtra(ShareSessionService.EXTRA_DIAG);
            if (status != null) statusText.setText(status);
            if (altText != null) {
                altText.setText(alternates == null || alternates.isEmpty()
                        ? "Only one address was found." : alternates);
            }
            if (diagText != null) {
                diagText.setText(diagnostics == null || diagnostics.isEmpty() ? "—" : diagnostics);
            }
            String address = addressFrom(url);
            networkText.setText(ssid != null || password != null
                    ? "Network  " + valueOr(ssid, "…")
                      + "\nPassword  " + valueOr(password, "…")
                      + (address.isEmpty() ? "" : "\nPhone address  " + address)
                    : "Pick a mode below to see what the laptop should join.");
            linkText.setText(url == null || url.isEmpty() ? "The link appears here when sharing starts." : url);
            codeText.setText(code == null || code.isEmpty() ? "— — — — — —" : code);
            modeText.setText(mode == null || mode.isEmpty() ? "Not sharing" : mode);
            sharing = url != null && !url.isEmpty();
            // Only the sharing state disables the mode buttons. A status message
            // must never be able to leave them stuck disabled.
            boolean active = intent.getBooleanExtra(ShareSessionService.EXTRA_ACTIVE, sharing);
            wifiButton.setEnabled(!active);
            hotspotButton.setEnabled(!active);
            stopButton.setEnabled(active);
            copyButton.setEnabled(sharing);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ShareSessionService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
        sendServiceCommand(ShareSessionService.ACTION_QUERY);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshScreenButton();
        CameraSessionService.attachRenderers(phoneCameraPreview, laptopCameraPreview);
        refreshCameraStatus();
    }

    /** The screen button doubles as the way to stop screen sharing while it runs. */
    private void refreshScreenButton() {
        if (screenButton != null) {
            boolean starting = ScreenCaptureService.isStarting();
            boolean running = ScreenCaptureService.isRunning();
            screenButton.setText(starting ? "Cancel screen startup" : running ? "Stop screen share" : "Share phone screen");
            if (starting) {
                if (statusText != null) statusText.setText("Starting screen capture. Keep PocketBridge open while Android prepares the stream…");
                screenStatusHandler.removeCallbacks(screenCaptureStatusPoll);
                screenStatusHandler.postDelayed(screenCaptureStatusPoll, 500);
            } else if (running && statusText != null) {
                statusText.setText("Screen capture is ready. Choose Watch screen on the laptop to view it.");
            } else if (!running && ScreenCaptureService.getSetupError() != null && statusText != null) {
                statusText.setText("Screen sharing could not start: " + ScreenCaptureService.getSetupError());
            }
        }
    }

    private final Runnable screenCaptureStatusPoll = this::refreshScreenButton;

    @Override protected void onDestroy() {
        chatHandler.removeCallbacksAndMessages(null);
        screenStatusHandler.removeCallbacksAndMessages(null);
        cameraHandler.removeCallbacksAndMessages(null);
        qrHandler.removeCallbacksAndMessages(null);
        closeQrScanner();
        if (laptopScreenDialog != null) laptopScreenDialog.dismiss();
        if (cameraFullscreenDialog != null) cameraFullscreenDialog.dismiss();
        CameraSessionService.detachRenderers(phoneCameraPreview, laptopCameraPreview);
        if (laptopView != null) {
            laptopView.stopLoading();
            laptopView.destroy();
            laptopView = null;
        }
        background.shutdownNow();
        chatWorker.shutdownNow();
        phoneUploadQueue.shutdown();
        super.onDestroy();
    }

    @Override protected void onStop() {
        chatHandler.removeCallbacks(chatPoll);
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(28), dp(24), dp(16));
        header.setBackgroundColor(COLOR_SURFACE);
        TextView brand = new TextView(this);
        brand.setText("POCKETBRIDGE");
        brand.setTextColor(COLOR_GREEN);
        brand.setTextSize(12);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand);
        TextView title = new TextView(this);
        title.setText("Share nearby.\nStay offline.");
        title.setTextColor(COLOR_INK);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(8), 0, dp(6));
        header.addView(title);
        TextView intro = new TextView(this);
        intro.setText("Choose how the laptop reaches this phone. Files and screen stay on your own network.");
        intro.setTextColor(COLOR_MUTED);
        intro.setTextSize(15);
        header.addView(intro);
        page.addView(header);

        // Five tabs: Connect, Screen, Library, Chat, Camera. Each one gets its own
        // scrolling column; only the chosen tab is visible.
        android.widget.FrameLayout tabHost = new android.widget.FrameLayout(this);
        ScrollView connectScroll = new ScrollView(this);
        LinearLayout content = tabColumn();
        connectScroll.addView(content);
        ScrollView screenScroll = new ScrollView(this);
        LinearLayout screenContent = tabColumn();
        screenScroll.addView(screenContent);
        ScrollView libraryScroll = new ScrollView(this);
        LinearLayout libraryContent = tabColumn();
        libraryScroll.addView(libraryContent);
        ScrollView chatScroll = new ScrollView(this);
        LinearLayout chatContent = tabColumn();
        chatScroll.addView(chatContent);
        ScrollView cameraScroll = new ScrollView(this);
        LinearLayout cameraContent = tabColumn();
        cameraScroll.addView(cameraContent);
        tabHost.addView(connectScroll);
        tabHost.addView(screenScroll);
        tabHost.addView(libraryScroll);
        tabHost.addView(chatScroll);
        tabHost.addView(cameraScroll);
        screenScroll.setVisibility(View.GONE);
        libraryScroll.setVisibility(View.GONE);
        chatScroll.setVisibility(View.GONE);
        cameraScroll.setVisibility(View.GONE);
        this.tabViews = new ScrollView[]{connectScroll, screenScroll, libraryScroll, chatScroll, cameraScroll};

        statusText = new TextView(this);
        statusText.setText("Ready when you are");
        statusText.setTextColor(COLOR_INK);
        statusText.setTextSize(17);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addCard(content, "STATUS", statusText);

        // Mode choice: the two ways the laptop can reach this phone.
        modeText = new TextView(this);
        modeText.setText("Not sharing");
        modeText.setTextColor(COLOR_GREEN);
        modeText.setTextSize(15);
        modeText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addCard(content, "ACTIVE MODE", modeText);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        wifiButton = button("Wi-Fi mode", COLOR_GREEN);
        wifiButton.setOnClickListener(v -> ensurePermissionsAndStart(ShareSessionService.ACTION_START_WIFI));
        hotspotButton = button("Hotspot mode", Color.rgb(31, 53, 66));
        hotspotButton.setOnClickListener(v -> ensurePermissionsAndStart(ShareSessionService.ACTION_START_HOTSPOT));
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1f);
        leftParams.rightMargin = dp(6);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rightParams.leftMargin = dp(6);
        modeRow.addView(wifiButton, leftParams);
        modeRow.addView(hotspotButton, rightParams);
        addCard(content, "HOW TO CONNECT", modeRow);

        TextView modeHelp = new TextView(this);
        modeHelp.setText("Wi-Fi mode: the laptop joins the same Wi-Fi this phone is on. Best when both are already on one network.\n\nHotspot mode: this phone creates its own private Wi-Fi. If your hotspot is already switched on in Android settings, PocketBridge reuses it.");
        modeHelp.setTextColor(COLOR_MUTED);
        modeHelp.setTextSize(13);
        modeHelp.setLineSpacing(dp(3), 1f);
        modeHelp.setPadding(dp(4), dp(4), dp(4), dp(14));
        content.addView(modeHelp);

        networkText = new TextView(this);
        networkText.setText("Pick a mode below to see what the laptop should join.");
        networkText.setTextColor(COLOR_INK);
        networkText.setTextSize(15);
        networkText.setLineSpacing(dp(5), 1f);
        addCard(content, "ON THE LAPTOP, JOIN", networkText);

        linkText = new TextView(this);
        linkText.setText("The link appears here when sharing starts.");
        linkText.setTextColor(COLOR_GREEN);
        linkText.setTextSize(19);
        linkText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addCard(content, "1 · OPEN THIS LINK", linkText);

        codeText = new TextView(this);
        codeText.setText("— — — — — —");
        codeText.setTextColor(COLOR_INK);
        codeText.setTextSize(34);
        codeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeText.setLetterSpacing(0.18f);
        addCard(content, "2 · THEN ENTER THIS CODE", codeText);

        altText = new TextView(this);
        altText.setText("Only one address was found.");
        altText.setTextColor(COLOR_GREEN);
        altText.setTextSize(15);
        altText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        altText.setLineSpacing(dp(5), 1f);
        addCard(content, "ALSO TRY THESE LINKS", altText);

        diagText = new TextView(this);
        diagText.setText("—");
        diagText.setTextColor(COLOR_MUTED);
        diagText.setTextSize(12);
        diagText.setLineSpacing(dp(3), 1f);
        addCard(content, "DIAGNOSTICS", diagText);

        copyButton = button("Copy link and code", COLOR_SOFT);
        copyButton.setTextColor(COLOR_GREEN);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> {
            String url = linkText.getText().toString();
            if (!url.startsWith("http://")) { Toast.makeText(this, "Start sharing first", Toast.LENGTH_SHORT).show(); return; }
            String code = codeText.getText().toString().replace(" ", "");
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("PocketBridge", url + "  code " + code));
            Toast.makeText(this, "Link and code copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyGap = matchWrap();
        copyGap.topMargin = dp(2);
        content.addView(copyButton, copyGap);

        Button sendFromPhone = button("Choose a file to send to laptop", COLOR_GREEN);
        sendFromPhone.setOnClickListener(v -> choosePhoneFileForLaptop());
        content.addView(sendFromPhone, matchWrap());

        LinearLayout qrCard = new LinearLayout(this);
        qrCard.setOrientation(LinearLayout.VERTICAL);
        TextView qrIntro = new TextView(this);
        qrIntro.setText("QR Share uses the camera only. It can transfer text and small files up to 128 KiB; for photos and videos, use Wi-Fi or Hotspot for much higher speed.");
        qrIntro.setTextColor(COLOR_MUTED);
        qrIntro.setTextSize(13);
        qrIntro.setLineSpacing(dp(3), 1f);
        qrCard.addView(qrIntro, matchWrap());
        qrTextInput = new EditText(this);
        qrTextInput.setHint("Text to share by QR");
        qrTextInput.setMinLines(2);
        qrTextInput.setMaxLines(4);
        qrCard.addView(qrTextInput, matchWrap());
        Button textQr = button("Show text QR", COLOR_GREEN);
        textQr.setOnClickListener(v -> {
            String text = qrTextInput.getText().toString();
            if (text.isEmpty()) { Toast.makeText(this, "Enter text to share.", Toast.LENGTH_SHORT).show(); return; }
            try { showQrFrames(QrTransferCodec.textFrames(randomQrId(), text), "Text"); }
            catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        qrCard.addView(textQr, matchWrap());
        Button fileQr = button("Choose a small file for QR", COLOR_SOFT);
        fileQr.setTextColor(COLOR_INK);
        fileQr.setOnClickListener(v -> chooseQrFile());
        qrCard.addView(fileQr, matchWrap());
        Button scanQr = button("Scan QR from another device", COLOR_SOFT);
        scanQr.setTextColor(COLOR_INK);
        scanQr.setOnClickListener(v -> scanQrFromCamera());
        qrCard.addView(scanQr, matchWrap());
        TextView qrHelp = new TextView(this);
        qrHelp.setText("On the laptop, open the Windows helper's local /qr.html page. It can show a QR for the laptop or scan the phone's QR frames.");
        qrHelp.setTextColor(COLOR_MUTED);
        qrHelp.setTextSize(12);
        qrHelp.setPadding(0, dp(8), 0, 0);
        qrCard.addView(qrHelp, matchWrap());
        addCard(content, "QR SHARE", qrCard);

        screenHelperLink = new EditText(this);
        screenHelperLink.setSingleLine(true);
        screenHelperLink.setHint("http://192.168.43.42:7070/?t=…");
        screenHelperLink.setTextSize(14);
        screenHelperLink.setText(getSavedHelperLink());
        MediaHttpServer.setLaptopHelperLink(getSavedHelperLink());
        watchHelperLink(screenHelperLink);
        addCard(content, "WINDOWS HELPER LINK · PASTE ONCE", screenHelperLink);
        laptopScreenStatus = new TextView(this);
        laptopScreenStatus.setText("Start the Windows helper and paste the LAN link for the laptop's Wi-Fi adapter. Keep the laptop awake for screen sharing.");
        laptopScreenStatus.setTextColor(COLOR_MUTED);
        laptopScreenStatus.setTextSize(13);
        laptopScreenStatus.setLineSpacing(dp(3), 1f);
        content.addView(laptopScreenStatus, matchWrap());

        LinearLayout powerControls = new LinearLayout(this);
        powerControls.setOrientation(LinearLayout.VERTICAL);
        String[][] powerActions = {{"Lock laptop", "lock"}, {"Wake display", "wake-display"},
                {"Wake & show sign-in (PIN needed)", "unlock"}, {"Put laptop to sleep", "sleep"}, {"Shut down laptop", "shutdown"}};
        for (String[] powerAction : powerActions) {
            Button actionButton = button(powerAction[0], ("shutdown".equals(powerAction[1]) || "sleep".equals(powerAction[1])) ? COLOR_SOFT : COLOR_GREEN);
            if ("shutdown".equals(powerAction[1]) || "sleep".equals(powerAction[1])) actionButton.setTextColor(COLOR_INK);
            actionButton.setOnClickListener(v -> {
                String action = powerAction[1];
                if ("shutdown".equals(action) || "sleep".equals(action)) {
                    boolean shutdown = "shutdown".equals(action);
                    new android.app.AlertDialog.Builder(this).setTitle(shutdown ? "Shut down laptop" : "Put laptop to sleep")
                            .setMessage(shutdown ? "Shut down Windows now? Unsaved work on the laptop may be lost and the connection will end."
                                    : "Put Windows to sleep now? The laptop will disconnect until it wakes.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton(shutdown ? "Shut down" : "Sleep", (dialog, which) -> postLaptopPowerAction(
                                    screenHelperLink.getText().toString().trim(), action, true, laptopScreenStatus)).show();
                } else {
                    postLaptopPowerAction(screenHelperLink.getText().toString().trim(), action, false, laptopScreenStatus);
                }
            });
            LinearLayout.LayoutParams actionLp = matchWrap(); actionLp.bottomMargin = dp(6);
            powerControls.addView(actionButton, actionLp);
        }
        addCard(content, "LAPTOP CONTROLS", powerControls);
        TextView powerNote = new TextView(this);
        powerNote.setText("Windows sign-in cannot be done remotely: \u201cWake & show sign-in\u201d only wakes the display so the PIN or password box is ready on the laptop. Wake works only while the laptop is awake enough to run the helper; a sleeping or shut-down laptop cannot be reached over Wi-Fi.");
        powerNote.setTextColor(COLOR_MUTED); powerNote.setTextSize(12);
        powerNote.setPadding(dp(4), 0, dp(4), dp(12)); content.addView(powerNote, matchWrap());

        stopButton = button("Stop sharing", Color.rgb(150, 60, 60));
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> sendServiceCommand(ShareSessionService.ACTION_STOP));
        LinearLayout.LayoutParams stopGap = matchWrap();
        stopGap.topMargin = dp(18);
        content.addView(stopButton, stopGap);

        // Everything from here belongs to the Screen tab.
        content = screenContent;
        TextView screenHeading = new TextView(this);
        screenHeading.setText("Share this phone's screen to the laptop, or open the laptop's live view here.");
        screenHeading.setTextColor(COLOR_MUTED);
        screenHeading.setTextSize(13);
        screenHeading.setLineSpacing(dp(3), 1f);
        screenHeading.setPadding(dp(4), 0, dp(4), dp(12));
        content.addView(screenHeading);

        screenButton = button("Share phone screen", Color.rgb(31, 53, 66));
        screenButton.setOnClickListener(v -> {
            if (ScreenCaptureService.isRunning() || ScreenCaptureService.isStarting()) {
                stopService(new Intent(this, ScreenCaptureService.class));
                statusText.setText("Screen sharing stopped. Files are still shared.");
                screenButton.setText("Share phone screen");
                screenStatusHandler.removeCallbacks(screenCaptureStatusPoll);
                return;
            }
            if (!sharing) {
                Toast.makeText(this, "Start a share first, then approve screen capture.", Toast.LENGTH_LONG).show();
                return;
            }
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        });
        LinearLayout.LayoutParams screenGap = matchWrap();
        screenGap.topMargin = dp(10);
        content.addView(screenButton, screenGap);

        // Shared helper URL and controls live in Connect. This view is only the viewer.

        // Laptop screen on the phone: the Windows helper already streams JPEG over
        // the hotspot, so show its own live page here instead of duplicating it.
        android.webkit.WebView laptopView = new android.webkit.WebView(this);
        this.laptopView = laptopView;
        android.webkit.WebSettings viewerSettings = laptopView.getSettings();
        viewerSettings.setJavaScriptEnabled(true);
        viewerSettings.setDomStorageEnabled(true);
        viewerSettings.setLoadWithOverviewMode(true);
        viewerSettings.setUseWideViewPort(true);
        // The helper streams the screen as a muted video. WebView refuses to
        // start any media without a tap unless told otherwise, which left the
        // laptop screen black on the phone even though frames were arriving.
        viewerSettings.setMediaPlaybackRequiresUserGesture(false);
        viewerSettings.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        viewerSettings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // The helper keeps its login in a cookie for the video and input sockets.
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(laptopView, true);
        laptopView.setWebChromeClient(new android.webkit.WebChromeClient());
        laptopView.setBackgroundColor(Color.BLACK);
        laptopView.setVisibility(View.GONE);
        laptopView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        laptopView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(380)));
        final Runnable[] screenStatusPoll = new Runnable[1];
        screenStatusPoll[0] = () -> {
            if (laptopView.getVisibility() != View.VISIBLE) return;
            laptopView.evaluateJavascript("(function(){var d=document.getElementById('dot');var v=document.getElementById('screenv');var i=document.getElementById('screenj');var s=d?d.className:'offline';var w=v&&v.videoWidth?v.videoWidth:(i?i.naturalWidth:0);var h=v&&v.videoHeight?v.videoHeight:(i?i.naturalHeight:0);return s+'|'+w+'x'+h})()", result -> {
                String state = result == null ? "" : result.replace("\\\"", "").replace("\"", "");
                if (state.startsWith("live|")) laptopScreenStatus.setText("Laptop screen live · " + state.substring(5));
                else if (state.startsWith("connecting|")) laptopScreenStatus.setText("Connecting to the laptop screen…");
                else if (state.startsWith("offline|")) laptopScreenStatus.setText("No screen frames received. Confirm Windows is awake, the helper is running, and the helper link is current.");
            });
            screenStatusHandler.postDelayed(screenStatusPoll[0], 1000);
        };
        laptopView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                screenStatusHandler.removeCallbacks(screenStatusPoll[0]);
                if (url.startsWith("http://127.0.0.1:" + MediaHttpServer.PORT + "/watch-laptop.html")
                        || "about:blank".equals(url)) return;
                laptopScreenStatus.setText("Viewer loaded · waiting for laptop frames…");
                screenStatusHandler.post(screenStatusPoll[0]);
            }
            @Override public void onReceivedError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) laptopScreenStatus.setText("Could not reach the laptop helper. Keep both devices on the same hotspot and confirm Windows Firewall allows PocketBridge on private networks.");
            }
            @Override public void onReceivedHttpError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) laptopScreenStatus.setText(response.getStatusCode() == 401
                        ? "The helper link was rejected. Copy a fresh link from the running Windows helper and paste it again."
                        : "The laptop helper returned HTTP " + response.getStatusCode() + ". Check the helper and try again.");
            }
        });
        Button watchPresentation = button("Watch Edge screen", COLOR_GREEN);
        watchPresentation.setOnClickListener(v -> {
            String code = codeText.getText().toString().trim();
            if (!sharing || !code.matches("[0-9]{6}")) {
                Toast.makeText(this, "Start sharing in Connect first.", Toast.LENGTH_SHORT).show();
                return;
            }
            screenStatusHandler.removeCallbacks(screenStatusPoll[0]);
            laptopScreenStatus.setText("Watching the screen selected in Edge.");
            laptopView.setVisibility(View.VISIBLE);
            laptopView.loadUrl("http://127.0.0.1:" + MediaHttpServer.PORT + "/watch-laptop.html?code=" + code);
        });
        content.addView(watchPresentation, matchWrap());
        Button showLaptop = button("View Windows helper", COLOR_SOFT);
        showLaptop.setOnClickListener(v -> {
            String link = screenHelperLink.getText().toString().trim();
            if (link.isEmpty()) {
                laptopScreenStatus.setText("Start PocketBridge Windows Control on the laptop, then paste its printed link above.");
                return;
            }
            laptopScreenStatus.setText("Checking the laptop link…");
            showLaptop.setEnabled(false);
            background.execute(() -> {
                try {
                    HelperLink reachable = HelperClient.resolve(link, message -> runOnUiThread(() -> laptopScreenStatus.setText(message)));
                    runOnUiThread(() -> {
                        showLaptop.setEnabled(true);
                        if (isFinishing() || this.laptopView == null) return;
                        laptopScreenStatus.setText("Connecting to the laptop screen at " + reachable.host + "…");
                        laptopView.setVisibility(View.VISIBLE);
                        laptopView.loadUrl(reachable.pageUrl());
                    });
                } catch (HelperClient.HelperException error) {
                    runOnUiThread(() -> {
                        showLaptop.setEnabled(true);
                        laptopScreenStatus.setText("Could not open the laptop screen: " + error.getMessage());
                    });
                }
            });
        });
        Button hideLaptop = button("Hide laptop screen", COLOR_SOFT);
        hideLaptop.setTextColor(COLOR_INK);
        hideLaptop.setOnClickListener(v -> { screenStatusHandler.removeCallbacks(screenStatusPoll[0]); laptopView.setVisibility(View.GONE); laptopView.loadUrl("about:blank"); });
        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams liveLeft = new LinearLayout.LayoutParams(0, -2, 1f);
        liveLeft.rightMargin = dp(6);
        LinearLayout.LayoutParams liveRight = new LinearLayout.LayoutParams(0, -2, 1f);
        liveRight.leftMargin = dp(6);
        liveRow.addView(showLaptop, liveLeft);
        liveRow.addView(hideLaptop, liveRight);
        addCard(content, "LAPTOP SCREEN ON THIS PHONE", liveRow);
        Button fullLaptop = button("Full screen", COLOR_SOFT);
        fullLaptop.setTextColor(COLOR_INK);
        fullLaptop.setOnClickListener(v -> showLaptopScreenFullscreen());
        content.addView(fullLaptop, matchWrap());
        content.addView(laptopView, new LinearLayout.LayoutParams(-1, dp(380)));
        laptopScreenOriginalLayout = laptopView.getLayoutParams();
        laptopScreenParent = (ViewGroup) laptopView.getParent();
        laptopScreenOriginalIndex = laptopScreenParent.indexOfChild(laptopView);

        TextView note = new TextView(this);
        note.setText("The code keeps the link private. Anyone who joins the network still needs it to see your files. Android asks for screen-capture approval each time.");
        note.setTextColor(COLOR_MUTED);
        note.setTextSize(13);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        content.addView(note);

        content = libraryContent;
        buildLibraryTab(content);
        content = chatContent;
        buildChatTab(content);
        content = cameraContent;
        buildCameraTab(content);

        page.addView(tabHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
        showTab(0);
    }

    /** Reparents the active laptop viewer into an immersive, edge-to-edge dialog. */
    private void showLaptopScreenFullscreen() {
        if (laptopView == null || laptopView.getVisibility() != View.VISIBLE) {
            Toast.makeText(this, "Connect to the laptop screen first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (laptopScreenDialog != null && laptopScreenDialog.isShowing()) return;
        if (laptopScreenParent == null || laptopView.getParent() != laptopScreenParent) {
            if (!(laptopView.getParent() instanceof ViewGroup)) return;
            laptopScreenParent = (ViewGroup) laptopView.getParent();
            laptopScreenOriginalIndex = laptopScreenParent.indexOfChild(laptopView);
            laptopScreenOriginalLayout = laptopView.getLayoutParams();
        }
        laptopScreenParent.removeView(laptopView);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.addView(laptopView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Button close = button("Close full screen", COLOR_SOFT);
        close.setTextColor(COLOR_INK);
        android.widget.FrameLayout.LayoutParams closeParams = new android.widget.FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(12), dp(12), 0);
        frame.addView(close, closeParams);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        laptopScreenDialog = dialog;
        dialog.setContentView(frame);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (laptopScreenParent != null && laptopView.getParent() == frame) {
                frame.removeView(laptopView);
                int index = laptopScreenOriginalIndex < 0 ? laptopScreenParent.getChildCount()
                        : Math.min(laptopScreenOriginalIndex, laptopScreenParent.getChildCount());
                laptopScreenParent.addView(laptopView, index, laptopScreenOriginalLayout);
            }
            laptopScreenDialog = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private android.webkit.WebView laptopView;
    private final android.os.Handler screenStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private ScrollView[] tabViews;
    private Button[] tabButtons;
    private LinearLayout libraryList;
    private TextView libraryStatus;
    private String libraryFilter = "all";
    private LinearLayout phoneLibrarySection;
    private LinearLayout laptopLibrarySection;
    private String laptopFilterKind = "all";
    private final Set<String> laptopDownloadsInProgress = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Set<String> laptopDownloadsComplete = java.util.Collections.synchronizedSet(new HashSet<>());
    private LinearLayout chatList;
    private ScrollView chatMessageScroll;
    private TextView chatStatus;
    private EditText chatInput;
    private boolean chatNotificationsPrimed;
    private boolean chatNeedsBottomScroll;
    private long lastChatNotificationTime;
    private final android.os.Handler chatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService phoneUploadQueue = Executors.newSingleThreadExecutor();
    /**
     * Network and disk work started from the UI. Bounded, so tapping a button
     * repeatedly queues work instead of starting a new thread per tap.
     */
    private final ExecutorService background = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "PocketBridge-ui-work");
        thread.setDaemon(true);
        return thread;
    });
    /** Chat reads run here, one at a time, never on the main thread. */
    private final ExecutorService chatWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PocketBridge-chat");
        thread.setDaemon(true);
        return thread;
    });
    private long shownChatVersion = -1;
    private boolean chatReadPending;
    private int libraryGeneration;
    private int laptopListGeneration;
    private final Runnable chatPoll = new Runnable() {
        @Override public void run() {
            refreshChat();
            chatHandler.postDelayed(this, 2500);
        }
    };

    private LinearLayout tabColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(18), dp(20), dp(24));
        return column;
    }

    /** Bottom navigation for the five primary PocketBridge sections. */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_SURFACE);
        bar.setPadding(dp(4), dp(8), dp(4), dp(10));
        bar.setElevation(dp(8));
        int[] icons = {R.drawable.ic_tab_connect, R.drawable.ic_tab_screen, R.drawable.ic_tab_library, R.drawable.ic_tab_chat, R.drawable.ic_tab_camera};
        String[] labels = {"Connect", "Screen", "Library", "Chat", "Camera"};
        tabButtons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = new Button(this);
            tab.setText(labels[i]);
            tab.setTextSize(11);
            tab.setAllCaps(false);
            tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tab.setBackgroundColor(Color.TRANSPARENT);
            tab.setCompoundDrawablesWithIntrinsicBounds(0, icons[i], 0, 0);
            tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(COLOR_MUTED));
            tab.setCompoundDrawablePadding(dp(3));
            tab.setPadding(0, dp(4), 0, 0);
            tab.setMinHeight(0);
            tab.setMinimumHeight(0);
            tab.setOnClickListener(v -> showTab(index));
            tabButtons[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return bar;
    }

    private void showTab(int index) {
        if (tabViews == null) return;
        for (int i = 0; i < tabViews.length; i++) tabViews[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        if (index == 3) {
            refreshChat();
            if (chatNeedsBottomScroll && chatMessageScroll != null) chatMessageScroll.post(() -> {
                chatMessageScroll.scrollTo(0, chatList.getBottom());
                chatNeedsBottomScroll = false;
            });
        }
        if (tabButtons != null) {
            for (int i = 0; i < tabButtons.length; i++) {
                int tint = i == index ? COLOR_GREEN : COLOR_MUTED;
                tabButtons[i].setTextColor(tint);
                tabButtons[i].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(tint));
            }
        }
        if (index == 1 || index == 2) syncHelperLinkFields();
        if (index == 3) {
            refreshChat();
            chatHandler.removeCallbacks(chatPoll);
            chatHandler.postDelayed(chatPoll, 2500);
        } else {
            chatHandler.removeCallbacks(chatPoll);
        }
        if (index == 1) refreshScreenButton();
        if (index == 4) refreshCameraStatus();
    }

    private void buildCameraTab(LinearLayout parent) {
        TextView intro = new TextView(this);
        intro.setText("Start a private, two-way camera call over your hotspot. The laptop and phone each have to start the call and approve their camera permission. Video stays on the local network.");
        intro.setTextColor(COLOR_MUTED);
        intro.setTextSize(14);
        intro.setLineSpacing(dp(3), 1f);
        addCard(parent, "CAMERA SHARE · BOTH SIDES APPROVE", intro);

        cameraStatus = new TextView(this);
        cameraStatus.setText("Camera is off. Start here, then open the Windows helper's Camera page on the laptop.");
        cameraStatus.setTextColor(COLOR_INK);
        cameraStatus.setTextSize(14);
        cameraStatus.setLineSpacing(dp(3), 1f);
        addCard(parent, "SESSION STATUS", cameraStatus);

        cameraToggle = button("Start camera session", COLOR_GREEN);
        cameraToggle.setOnClickListener(v -> toggleCameraSession());
        parent.addView(cameraToggle, matchWrap());

        Button copyCameraDetails = button("Copy phone link and access code", COLOR_SOFT);
        copyCameraDetails.setTextColor(COLOR_INK);
        copyCameraDetails.setOnClickListener(v -> {
            String link = linkText == null ? "" : linkText.getText().toString().trim();
            String accessCode = codeText == null ? "" : codeText.getText().toString().replaceAll("\\s", "");
            if (!link.startsWith("http://") || accessCode.length() != 6) {
                Toast.makeText(this, "Start Wi-Fi or hotspot sharing first.", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketBridge camera", link + "\nCode: " + accessCode));
                Toast.makeText(this, "Phone link and code copied. Enter them on the laptop Camera page.", Toast.LENGTH_LONG).show();
            }
        });
        parent.addView(copyCameraDetails, matchWrap());

        phoneCameraPreview = new org.webrtc.SurfaceViewRenderer(this);
        // SurfaceView draws camera pixels on a separate surface behind the view.
        // An opaque background on the renderer itself covers those pixels.
        phoneCameraPreview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240)));
        phoneCameraPreview.setEnableHardwareScaler(true);
        phoneCameraPreview.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        phoneCameraPreview.setVisibility(View.GONE);
        addCard(parent, "PHONE CAMERA · SENT TO LAPTOP", phoneCameraPreview);

        laptopCameraPreview = new org.webrtc.SurfaceViewRenderer(this);
        laptopCameraPreview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240)));
        laptopCameraPreview.setEnableHardwareScaler(true);
        laptopCameraPreview.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        laptopCameraPreview.setVisibility(View.GONE);
        addCard(parent, "LAPTOP CAMERA · RECEIVED HERE", laptopCameraPreview);
        CameraSessionService.attachRenderers(phoneCameraPreview, laptopCameraPreview);

        Button fullscreen = button("Full screen laptop camera", COLOR_SOFT);
        fullscreen.setTextColor(COLOR_INK);
        fullscreen.setOnClickListener(v -> showCameraFullscreen());
        parent.addView(fullscreen, matchWrap());

        TextView help = new TextView(this);
        help.setText("On the laptop, run the matching Windows helper. In the PocketBridge hotspot page choose Camera, open the camera window and allow Edge to use the webcam. Keep that hotspot tab open during the call.");
        help.setTextColor(COLOR_MUTED);
        help.setTextSize(12);
        help.setLineSpacing(dp(3), 1f);
        help.setPadding(dp(4), dp(12), dp(4), 0);
        parent.addView(help, matchWrap());
        refreshCameraStatus();
    }

    private void toggleCameraSession() {
        if (CameraSessionService.isRunning()) {
            stopService(new Intent(this, CameraSessionService.class));
            refreshCameraStatus();
            return;
        }
        if (CameraSessionService.isStarting()) {
            stopService(new Intent(this, CameraSessionService.class));
            cameraStatus.setText("Camera startup cancelled.");
            return;
        }
        if (!sharing) {
            cameraStatus.setText("Start a Wi-Fi or hotspot share first so the laptop can reach the phone.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        startCameraSession();
    }

    private void startCameraSession() {
        if (CameraSessionService.isRunning() || CameraSessionService.isStarting()) {
            refreshCameraStatus();
            return;
        }
        Intent service = new Intent(this, CameraSessionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        cameraStatus.setText("Starting the camera. Your laptop must also start and approve its camera.");
        if (phoneCameraPreview != null) phoneCameraPreview.setVisibility(View.GONE);
        if (laptopCameraPreview != null) laptopCameraPreview.setVisibility(View.GONE);
        cameraHandler.removeCallbacks(cameraStatusPoll);
        cameraHandler.postDelayed(cameraStatusPoll, 500);
        CameraSessionService.attachRenderers(phoneCameraPreview, laptopCameraPreview);
    }

    private void refreshCameraStatus() {
        if (cameraStatus == null) return;
        if (CameraSessionService.isRunning() || CameraSessionService.isStarting()) {
            boolean starting = CameraSessionService.isStarting();
            cameraToggle.setText(starting ? "Cancel camera startup" : "Stop camera session");
            cameraStatus.setText(CameraSessionService.statusText());
            if (phoneCameraPreview != null) phoneCameraPreview.setVisibility(starting ? View.GONE : View.VISIBLE);
            if (laptopCameraPreview != null) laptopCameraPreview.setVisibility(starting ? View.GONE : View.VISIBLE);
            cameraHandler.removeCallbacks(cameraStatusPoll);
            cameraHandler.postDelayed(cameraStatusPoll, starting ? 500 : 1000);
        } else {
            cameraToggle.setText("Start camera session");
            String lastStatus = CameraSessionService.statusText();
            cameraStatus.setText(lastStatus == null || lastStatus.equals("Camera is off.")
                    ? "Camera is off. Start here, then open the Windows helper's Camera page on the laptop."
                    : lastStatus);
            if (phoneCameraPreview != null) phoneCameraPreview.setVisibility(View.GONE);
            if (laptopCameraPreview != null) laptopCameraPreview.setVisibility(View.GONE);
            cameraHandler.removeCallbacks(cameraStatusPoll);
        }
    }

    private final android.os.Handler cameraHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cameraStatusPoll = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) refreshCameraStatus();
        }
    };

    private void showCameraFullscreen() {
        if (laptopCameraPreview == null || laptopCameraPreview.getVisibility() != View.VISIBLE) {
            cameraStatus.setText("Start the camera session and connect from Edge before opening full screen.");
            return;
        }
        if (cameraFullscreenDialog != null && cameraFullscreenDialog.isShowing()) return;
        if (!(laptopCameraPreview.getParent() instanceof ViewGroup)) return;
        cameraPreviewParent = (ViewGroup) laptopCameraPreview.getParent();
        cameraPreviewOriginalIndex = cameraPreviewParent.indexOfChild(laptopCameraPreview);
        cameraPreviewOriginalLayout = laptopCameraPreview.getLayoutParams();
        cameraPreviewParent.removeView(laptopCameraPreview);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.addView(laptopCameraPreview, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Button close = button("Close", COLOR_SOFT);
        android.widget.FrameLayout.LayoutParams closeLp = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        closeLp.setMargins(0, dp(12), dp(12), 0);
        frame.addView(close, closeLp);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        cameraFullscreenDialog = dialog;
        dialog.setContentView(frame);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (cameraPreviewParent != null && laptopCameraPreview.getParent() == frame) {
                frame.removeView(laptopCameraPreview);
                int index = cameraPreviewOriginalIndex < 0 ? cameraPreviewParent.getChildCount()
                        : Math.min(cameraPreviewOriginalIndex, cameraPreviewParent.getChildCount());
                cameraPreviewParent.addView(laptopCameraPreview, index, cameraPreviewOriginalLayout);
            }
            cameraFullscreenDialog = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Library tab: laptop files only. */
    private void buildLibraryTab(LinearLayout parent) {
        laptopLibrarySection = new LinearLayout(this);
        laptopLibrarySection.setOrientation(LinearLayout.VERTICAL);
        parent.addView(laptopLibrarySection, matchWrap());

        TextView laptopIntro = new TextView(this);
        laptopIntro.setText("Browse the laptop from this phone. Leave the folder path empty for the helper's shared folder, or paste a Windows folder or file path. Searching is performed on the laptop.");
        laptopIntro.setTextColor(COLOR_MUTED);
        laptopIntro.setTextSize(13);
        laptopIntro.setLineSpacing(dp(3), 1f);
        addCard(laptopLibrarySection, "LAPTOP FILES · SAME HOTSPOT", laptopIntro);

        libraryHelperLink = new EditText(this);
        libraryHelperLink.setSingleLine(true);
        libraryHelperLink.setTextSize(14);
        libraryHelperLink.setHint("Paste laptop helper link · http://…:7070/?t=…");
        libraryHelperLink.setText(getSavedHelperLink());
        watchHelperLink(libraryHelperLink);
        addCard(laptopLibrarySection, "LAPTOP HELPER LINK", libraryHelperLink);

        laptopFolderPath = new EditText(this);
        laptopFolderPath.setSingleLine(true);
        laptopFolderPath.setTextSize(14);
        laptopFolderPath.setHint("Optional Windows folder or file path · C:\\Users\\…\\Videos");
        addCard(laptopLibrarySection, "LAPTOP FOLDER OR FILE PATH", laptopFolderPath);

        EditText laptopSearchField = new EditText(this);
        laptopSearchField.setSingleLine(true);
        laptopSearchField.setTextSize(14);
        laptopSearchField.setHint("Search laptop files");
        addCard(laptopLibrarySection, "SEARCH", laptopSearchField);

        LinearLayout laptopKinds = new LinearLayout(this);
        laptopKinds.setOrientation(LinearLayout.HORIZONTAL);
        String[] remoteKinds = {"all", "image", "video", "audio", "other"};
        String[] remoteLabels = {"All", "Photos", "Videos", "Audio", "Other"};
        laptopKindButtons = new Button[remoteKinds.length];
        for (int i = 0; i < remoteKinds.length; i++) {
            final String kind = remoteKinds[i];
            Button chip = button(remoteLabels[i], "all".equals(kind) ? COLOR_GREEN : COLOR_SOFT);
            if (!"all".equals(kind)) chip.setTextColor(COLOR_INK);
            chip.setTextSize(11);
            laptopKindButtons[i] = chip;
            chip.setOnClickListener(v -> setLaptopFilterKind(kind));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(3);
            laptopKinds.addView(chip, lp);
        }
        addCard(laptopLibrarySection, "FILE TYPE", laptopKinds);

        TextView laptopStatusView = new TextView(this);
        laptopStatusView.setText("The helper shares Downloads by default. Enter a Windows folder or exact file path to switch locations.");
        laptopStatusView.setTextColor(COLOR_MUTED);
        laptopStatusView.setTextSize(12);
        laptopStatusView.setPadding(dp(4), 0, dp(4), dp(10));
        laptopLibrarySection.addView(laptopStatusView);

        laptopFileList = new ListView(this) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                android.view.ViewParent parent = getParent();
                if (parent != null) {
                    int action = event.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
        laptopFileList.setDivider(new android.graphics.drawable.ColorDrawable(COLOR_LINE));
        laptopFileList.setDividerHeight(dp(1));
        laptopFileList.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(380)));
        laptopFileAdapter = new LaptopFileAdapter();
        laptopFileList.setAdapter(laptopFileAdapter);
        addCard(laptopLibrarySection, "LAPTOP FILES", laptopFileList);

        Button loadLaptop = button("Load laptop files", COLOR_GREEN);
        loadLaptop.setOnClickListener(v -> {
            String link = libraryHelperLink.getText().toString().trim();
            if (link.isEmpty()) {
                laptopStatusView.setText("Start the Windows helper and paste the LAN link for the laptop's Wi-Fi adapter.");
                return;
            }
            String requestedPath = laptopFolderPath.getText().toString().trim();
            laptopStatusView.setText(requestedPath.isEmpty() ? "Reading the laptop folder…" : "Switching to that laptop location…");
            laptopFileAdapter.setItems(java.util.Collections.emptyList(), null);
            loadLaptop.setEnabled(false);
            fetchLaptopFiles(link, requestedPath, laptopStatusView, laptopSearchField.getText().toString().trim(), () -> loadLaptop.setEnabled(true));
        });
        LinearLayout.LayoutParams loadGap = matchWrap();
        loadGap.topMargin = dp(2);
        laptopLibrarySection.addView(loadLaptop, loadGap);
        this.laptopFilter = laptopSearchField;
        this.laptopStatus = laptopStatusView;
        laptopSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterLaptopRows(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private ListView laptopFileList;
    private LaptopFileAdapter laptopFileAdapter;
    private TextView laptopStatus;
    private java.io.File pendingChatSaveSource;
    private String pendingChatSaveName;

    private void showLibrarySource(boolean laptop) {
        if (phoneLibrarySection == null || laptopLibrarySection == null) return;
        phoneLibrarySection.setVisibility(laptop ? View.GONE : View.VISIBLE);
        laptopLibrarySection.setVisibility(laptop ? View.VISIBLE : View.GONE);
        if (phoneLibrarySourceButton != null) {
            phoneLibrarySourceButton.setBackgroundColor(laptop ? COLOR_SOFT : COLOR_GREEN);
            phoneLibrarySourceButton.setTextColor(laptop ? COLOR_INK : Color.WHITE);
        }
        if (laptopLibrarySourceButton != null) {
            laptopLibrarySourceButton.setBackgroundColor(laptop ? COLOR_GREEN : COLOR_SOFT);
            laptopLibrarySourceButton.setTextColor(laptop ? Color.WHITE : COLOR_INK);
        }
        syncHelperLinkFields();
    }

    private void setLaptopFilterKind(String kind) {
        laptopFilterKind = kind;
        if (laptopKindButtons != null) {
            String[] values = {"all", "image", "video", "audio", "other"};
            for (int i = 0; i < laptopKindButtons.length; i++) {
                boolean selected = values[i].equals(kind);
                laptopKindButtons[i].setBackgroundColor(selected ? COLOR_GREEN : COLOR_SOFT);
                laptopKindButtons[i].setTextColor(selected ? Color.WHITE : COLOR_INK);
            }
        }
        filterLaptopRows();
    }

    private String getSavedHelperLink() {
        return getPreferences(MODE_PRIVATE).getString("laptop_helper_link", "");
    }

    private void watchHelperLink(EditText input) {
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (syncingHelperLinks) return;
                String value = s.toString();
                getPreferences(MODE_PRIVATE).edit().putString("laptop_helper_link", value).apply();
                MediaHttpServer.setLaptopHelperLink(value);
                EditText other = input == screenHelperLink ? libraryHelperLink : screenHelperLink;
                if (other != null && !other.getText().toString().equals(value)) {
                    syncingHelperLinks = true;
                    other.setText(value);
                    other.setSelection(other.getText().length());
                    syncingHelperLinks = false;
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private void syncHelperLinkFields() {
        String saved = getSavedHelperLink();
        syncingHelperLinks = true;
        if (screenHelperLink != null && !screenHelperLink.getText().toString().equals(saved)) screenHelperLink.setText(saved);
        if (libraryHelperLink != null && !libraryHelperLink.getText().toString().equals(saved)) libraryHelperLink.setText(saved);
        syncingHelperLinks = false;
    }

    /** Applies type/search filters to recycled laptop-file rows. */
    private void filterLaptopRows() {
        if (laptopFileAdapter == null || laptopFilter == null) return;
        int shown = laptopFileAdapter.filter(laptopFilter.getText().toString(), laptopFilterKind);
        if (laptopStatus != null && laptopFileAdapter.getTotalCount() > 0) {
            laptopStatus.setText(shown + " of " + laptopFileAdapter.getTotalCount()
                    + " laptop files shown. Photos open to view, videos/audio open to play, other files open in a compatible app.");
        }
    }

    private static final int LAPTOP_PAGE_SIZE = 500;
    private static final int LAPTOP_MAX_ROWS = 5000;

    /**
     * Reads the helper's shared folder page by page and fills the Library tab.
     * Each page is a short request with retries, so a large folder or one slow
     * moment on the hotspot no longer fails the whole list.
     */
    private void fetchLaptopFiles(String link, String requestedPath, TextView status, String initialQuery, Runnable done) {
        final int generation = ++laptopListGeneration;
        background.execute(() -> {
            try {
                HelperLink helper = HelperClient.resolve(link, message -> runOnUiThread(() -> status.setText(message)));
                String selectedPath = requestedPath.isEmpty() ? "@default" : requestedPath;
                byte[] payload = new JSONObject().put("share_dir", selectedPath).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                HelperClient.Response chosen = HelperClient.request("POST", helper.url("/settings"), payload,
                        HelperClient.CONNECT_TIMEOUT_MS, HelperClient.READ_TIMEOUT_MS, 2);
                if (!chosen.ok()) throw new HelperClient.HelperException(HelperClient.errorMessage(chosen.status, chosen.body), chosen.status);
                String exactFile = chosen.json().optString("share_file", "");
                String effectiveQuery = exactFile.isEmpty() ? initialQuery : "";
                runOnUiThread(() -> status.setText(requestedPath.isEmpty()
                        ? "Loading the helper's default shared folder…"
                        : exactFile.isEmpty() ? "Location selected. Loading its files…" : "Exact file selected. Loading it…"));
                int offset = 0;
                int shown = 0;
                boolean truncated = false;
                int total = 0;
                while (shown < LAPTOP_MAX_ROWS) {
                    HelperClient.Response response = HelperClient.request("GET",
                            helper.url("/files", "offset", String.valueOf(offset), "limit", String.valueOf(LAPTOP_PAGE_SIZE),
                                    "refresh", offset == 0 ? "1" : "0", "q", effectiveQuery),
                            null, HelperClient.CONNECT_TIMEOUT_MS, 30000, 3);
                    if (!response.ok()) throw new HelperClient.HelperException(HelperClient.errorMessage(response.status, response.body), response.status);
                    HelperClient.FilesPage page = HelperClient.parseFilesPage(response.body, offset);
                    total = page.total;
                    truncated = page.truncated;
                    shown += page.files.size();
                    final int loaded = shown;
                    final int expected = total;
                    runOnUiThread(() -> {
                        if (generation != laptopListGeneration) return;
                        laptopFileAdapter.appendItems(page.files, helper);
                        status.setText("Loaded " + loaded + (expected > loaded ? " of " + expected : "") + " laptop files…");
                    });
                    if (!page.hasMore || page.files.isEmpty()) break;
                    offset = page.nextOffset;
                }
                final int finalShown = shown;
                final int finalTotal = total;
                final boolean finalTruncated = truncated;
                runOnUiThread(() -> {
                    if (generation != laptopListGeneration) return;
                    if (finalShown == 0) {
                        status.setText("No files found in the helper's shared folder. Enter another laptop folder path above.");
                    } else {
                        filterLaptopRows();
                        String more = finalTotal > finalShown ? " Showing the newest " + finalShown + " of " + finalTotal + "." : "";
                        String partial = finalTruncated ? " The folder is very large, so only part of it was read; enter a smaller folder path to see the rest." : "";
                        if (!more.isEmpty() || !partial.isEmpty()) status.setText(status.getText() + more + partial);
                    }
                    done.run();
                });
            } catch (HelperClient.HelperException error) {
                failLaptopList(generation, status, error.getMessage(), done);
            } catch (java.io.IOException error) {
                failLaptopList(generation, status, HelperClient.networkMessage(error), done);
            } catch (Exception error) {
                failLaptopList(generation, status, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), done);
            }
        });
    }

    private void failLaptopList(int generation, TextView status, String message, Runnable done) {
        runOnUiThread(() -> {
            if (generation == laptopListGeneration) status.setText("Could not read the laptop folder: " + message);
            done.run();
        });
    }

    private final class LaptopFileAdapter extends BaseAdapter {
        private final List<JSONObject> all = new ArrayList<>();
        private final List<JSONObject> visible = new ArrayList<>();
        private HelperLink helper;

        void setItems(List<JSONObject> items, HelperLink helperLink) {
            all.clear();
            if (items != null) all.addAll(items);
            helper = helperLink;
            filter(laptopFilter == null ? "" : laptopFilter.getText().toString(), laptopFilterKind);
        }

        void appendItems(List<JSONObject> items, HelperLink helperLink) {
            if (items != null) all.addAll(items);
            helper = helperLink;
            filter(laptopFilter == null ? "" : laptopFilter.getText().toString(), laptopFilterKind);
        }

        int filter(String queryText, String kind) {
            String query = queryText == null ? "" : queryText.trim().toLowerCase(java.util.Locale.ROOT);
            visible.clear();
            for (JSONObject item : all) {
                String name = item.optString("name", "File");
                String mime = item.optString("mime", "");
                if (mime.isEmpty()) mime = MediaTypeClassifier.mimeType(name, null);
                String path = item.optString("path", name);
                boolean typeMatches = "all".equals(kind)
                        || ("other".equals(kind)
                            ? !(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))
                            : mime.startsWith(kind + "/"));
                if (typeMatches && (query.isEmpty() || (name + " " + path + " " + mime).toLowerCase(java.util.Locale.ROOT).contains(query))) visible.add(item);
            }
            notifyDataSetChanged();
            return visible.size();
        }

        int getTotalCount() { return all.size(); }
        @Override public int getCount() { return visible.size(); }
        @Override public JSONObject getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView detail;
            Button download;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof View[]) {
                row = (LinearLayout) convertView;
                View[] views = (View[]) convertView.getTag();
                title = (TextView) views[0];
                detail = (TextView) views[1];
                download = (Button) views[2];
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(9), dp(10), dp(9));
                title = new TextView(MainActivity.this);
                title.setTextColor(COLOR_INK);
                title.setTextSize(14);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                detail = new TextView(MainActivity.this);
                detail.setTextColor(COLOR_MUTED);
                detail.setTextSize(11);
                download = button("Download to phone", COLOR_SOFT);
                download.setTextColor(COLOR_INK);
                download.setTextSize(12);
                download.setMinHeight(dp(38));
                row.addView(title);
                row.addView(detail);
                LinearLayout.LayoutParams downloadLp = matchWrap();
                downloadLp.topMargin = dp(5);
                row.addView(download, downloadLp);
                row.setTag(new View[]{title, detail, download});
            }
            JSONObject item = getItem(position);
            String name = item.optString("name", "File");
            String mime = item.optString("mime", "");
            if (mime.isEmpty()) mime = MediaTypeClassifier.mimeType(name, null);
            String path = item.optString("path", name);
            String type = mime.startsWith("video/") ? "VIDEO"
                    : mime.startsWith("audio/") ? "AUDIO"
                    : mime.startsWith("image/") ? "PHOTO" : "FILE";
            title.setText(name);
            detail.setText(type + " · " + android.text.format.Formatter.formatShortFileSize(MainActivity.this, item.optLong("size")) + "\n" + path);
            final String relative = item.optString("url", "");
            final String openMime = mime;
            final HelperLink currentHelper = helper;
            final String downloadKey = currentHelper == null || relative.isEmpty() ? relative : currentHelper.url(relative);
            boolean downloading = laptopDownloadsInProgress.contains(downloadKey);
            boolean downloaded = laptopDownloadsComplete.contains(downloadKey);
            download.setText(downloading ? "Downloading…" : downloaded ? "Downloaded · in chat" : "Download to phone");
            download.setEnabled(currentHelper != null && !relative.isEmpty() && !downloading);
            download.setOnClickListener(view -> {
                if (currentHelper == null || relative.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Reload laptop files to refresh this link.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (laptopDownloadsInProgress.add(downloadKey)) {
                    filterLaptopRows();
                    downloadLaptopFile(currentHelper, relative, downloadKey, name, item.optLong("size", -1));
                }
            });
            row.setOnClickListener(view -> {
                if (currentHelper == null || relative.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Reload laptop files to refresh this link.", Toast.LENGTH_SHORT).show();
                    return;
                }
                openLaptopFile(android.net.Uri.parse(currentHelper.url(relative)), openMime);
            });
            return row;
        }
    }

    /** Opens a laptop file in an app that can stream it from the helper. */
    private void openLaptopFile(android.net.Uri fileUri, String mime) {
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            openVideoWithChoice(fileUri, mime, 0);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(fileUri, mime);
        try {
            startActivity(Intent.createChooser(view, "Open with"));
        } catch (Exception noTypedViewer) {
            // Many viewers only take content:// files; a browser can open any http link.
            try { startActivity(new Intent(Intent.ACTION_VIEW, fileUri)); }
            catch (Exception error) { Toast.makeText(this, "No installed app can open this file type.", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void downloadLaptopFile(HelperLink helper, String relativeUrl, String downloadKey, String name, long listedSize) {
        background.execute(() -> {
            java.io.File received = null;
            try {
                String url = helper.url(relativeUrl);
                received = LaptopFileTransfer.download(this, url,
                        new java.io.File(getFilesDir(), "received"), name, listedSize);
                long bytes = received.length();
                ChatStore.appendFile(this, LaptopFileTransfer.safeName(name), received.getName(), bytes, "laptop");
                laptopDownloadsInProgress.remove(downloadKey);
                laptopDownloadsComplete.add(downloadKey);
                runOnUiThread(() -> {
                    if (laptopFileAdapter != null) laptopFileAdapter.notifyDataSetChanged();
                    Toast.makeText(this, "Saved on this phone and added to Share chat.", Toast.LENGTH_LONG).show();
                    showTab(3);
                });
            } catch (Exception error) {
                if (received != null) { /*noinspection ResultOfMethodCallIgnored*/ received.delete(); }
                laptopDownloadsInProgress.remove(downloadKey);
                String message = error.getMessage() == null ? "Could not download this laptop file." : error.getMessage();
                runOnUiThread(() -> {
                    if (laptopFileAdapter != null) laptopFileAdapter.notifyDataSetChanged();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void postLaptopPowerAction(String link, String action, boolean confirmed, TextView status) {
        status.setText("Sending to the laptop…");
        background.execute(() -> {
            try {
                HelperLink helper = HelperClient.resolve(link, message -> runOnUiThread(() -> status.setText(message)));
                byte[] payload = new JSONObject().put("action", action).put("confirm", confirmed).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                // Never retry a power action automatically: a lost reply does not
                // mean the laptop ignored it, and a second shutdown is not wanted.
                HelperClient.Response response = HelperClient.request("POST", helper.url("/power"), payload,
                        HelperClient.CONNECT_TIMEOUT_MS, 10000, 1);
                if (!response.ok()) throw new HelperClient.HelperException(HelperClient.errorMessage(response.status, response.body), response.status);
                String message = response.json().optString("message", "Windows command sent: " + action + ".");
                runOnUiThread(() -> status.setText(message));
            } catch (HelperClient.HelperException error) {
                runOnUiThread(() -> status.setText("Laptop control failed: " + error.getMessage()));
            } catch (java.io.IOException error) {
                runOnUiThread(() -> status.setText("Laptop control failed: " + HelperClient.networkMessage(error)));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Laptop control failed: " + error.getMessage()));
            }
        });
    }

    private void refreshLibrary() {
        if (libraryList == null) return;
        final String kind = libraryFilter;
        // Only the newest request may fill the list; switching filters quickly
        // used to let two slower reads both add their rows.
        final int generation = ++libraryGeneration;
        libraryList.removeAllViews();
        libraryStatus.setText("Reading your media…");
        background.execute(() -> {
            List<MediaRepository.Item> items = new ArrayList<>();
            String message;
            try {
                MediaRepository repository = new MediaRepository(this);
                MediaRepository.Page page = repository.listPage(kind, 0, 60);
                items.addAll(page.items);
                message = items.isEmpty()
                        ? "Nothing to show here yet. Allow photo and video access, then start a share."
                        : items.size() + " item" + (items.size() == 1 ? "" : "s")
                          + (page.hasMore ? " (first 60 shown)" : "") + " · tap one to open it.";
            } catch (Exception error) {
                message = "Could not read the media library: " + error.getMessage();
            }
            final String status = message;
            runOnUiThread(() -> {
                if (generation != libraryGeneration || isFinishing()) return;
                libraryList.removeAllViews();
                libraryStatus.setText(status);
                for (MediaRepository.Item item : items) {
                    Button row = button(item.name + "\n" + android.text.format.Formatter.formatShortFileSize(this, item.size), COLOR_SURFACE);
                    row.setTextColor(COLOR_INK);
                    row.setTextSize(13);
                    row.setAllCaps(false);
                    row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    row.setMinHeight(dp(52));
                    row.setOnClickListener(v -> openMedia(item));
                    LinearLayout.LayoutParams lp = matchWrap();
                    lp.topMargin = dp(4);
                    libraryList.addView(row, lp);
                }
            });
        });
    }

    private void openMedia(MediaRepository.Item item) {
        if (item.mime != null && item.mime.startsWith("video/")) {
            openVideoWithChoice(item.uri, item.mime, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(item.uri, item.mime == null || item.mime.isEmpty() ? "*/*" : item.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "No app on this phone can open that file.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Chat tab: the same conversation the laptop sees, answered from here. */
    private void buildChatTab(LinearLayout parent) {
        chatStatus = new TextView(this);
        chatStatus.setText("Messages stay on this phone and the laptop, on your own network.");
        chatStatus.setTextColor(COLOR_MUTED);
        chatStatus.setTextSize(12);
        chatStatus.setLineSpacing(dp(3), 1f);
        parent.addView(chatStatus);

        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(dp(2), dp(6), dp(2), dp(6));
        chatMessageScroll = new ScrollView(this) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                android.view.ViewParent parent = getParent();
                if (parent != null) {
                    int action = event.getActionMasked();
                    if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                }
                return super.dispatchTouchEvent(event);
            }
        };
        chatMessageScroll.setFillViewport(true);
        chatMessageScroll.setVerticalScrollBarEnabled(true);
        chatMessageScroll.addView(chatList);
        chatMessageScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(360)));
        addCard(parent, "SHARE CHAT · SCROLL TO BROWSE", chatMessageScroll);

        chatInput = new EditText(this);
        chatInput.setHint("Write a message for the laptop");
        chatInput.setTextSize(14);
        chatInput.setMaxLines(3);
        addCard(parent, "YOUR MESSAGE", chatInput);

        Button send = button("Send message", COLOR_GREEN);
        send.setOnClickListener(v -> {
            String text = chatInput.getText().toString().trim();
            if (text.isEmpty()) return;
            chatInput.setText("");
            chatWorker.execute(() -> {
                ChatStore.append(this, text, "phone");
                runOnUiThread(this::refreshChat);
            });
        });
        LinearLayout.LayoutParams sendGap = matchWrap();
        sendGap.topMargin = dp(4);
        parent.addView(send, sendGap);

        Button attach = button("＋ Share a photo, video or file", COLOR_SOFT);
        attach.setTextColor(COLOR_INK);
        attach.setOnClickListener(v -> choosePhoneFileForLaptop());
        LinearLayout.LayoutParams attachGap = matchWrap();
        attachGap.topMargin = dp(8);
        parent.addView(attach, attachGap);

        Button clear = button("Clear conversation", COLOR_SOFT);
        clear.setTextColor(COLOR_INK);
        clear.setOnClickListener(v -> chatWorker.execute(() -> {
            ChatStore.clear(this);
            runOnUiThread(this::refreshChat);
        }));
        LinearLayout.LayoutParams clearGap = matchWrap();
        clearGap.topMargin = dp(8);
        parent.addView(clear, clearGap);
    }

    /**
     * Reads the conversation off the main thread and redraws only when it
     * changed. Reading and rebuilding every message on the main thread every
     * 2.5 seconds, while server threads held the same file lock, was one of
     * the ways the app froze.
     */
    private void refreshChat() {
        if (chatList == null || chatReadPending) return;
        chatReadPending = true;
        try {
            chatWorker.execute(() -> {
                long version = ChatStore.version();
                org.json.JSONArray messages = version == shownChatVersion ? null : ChatStore.read(this);
                runOnUiThread(() -> {
                    chatReadPending = false;
                    if (messages == null || isFinishing()) return;
                    shownChatVersion = version;
                    renderChat(messages);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            chatReadPending = false;
        }
    }

    private void renderChat(org.json.JSONArray messages) {
        if (chatList == null) return;
        boolean chatVisible = tabViews != null && tabViews.length > 3 && tabViews[3].getVisibility() == View.VISIBLE;
        boolean wasNearBottom = chatVisible && chatMessageScroll != null
                && chatList.getBottom() - chatMessageScroll.getScrollY() - chatMessageScroll.getHeight() < dp(48);
        int previousScroll = chatMessageScroll == null ? 0 : chatMessageScroll.getScrollY();
        long newestTime = 0;
        JSONObject newestLaptopAttachment = null;
        List<String> newLaptopFileNames = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            long time = item.optLong("time", 0);
            newestTime = Math.max(newestTime, time);
            if (chatNotificationsPrimed && "laptop".equals(item.optString("from"))
                    && item.optJSONObject("attachment") != null && time > lastChatNotificationTime) {
                newestLaptopAttachment = item.optJSONObject("attachment");
                newLaptopFileNames.add(newestLaptopAttachment.optString("name", "a file"));
            }
        }
        boolean showIncomingFileAlert = chatNotificationsPrimed && newestLaptopAttachment != null;
        chatNotificationsPrimed = true;
        lastChatNotificationTime = Math.max(lastChatNotificationTime, newestTime);
        chatList.removeAllViews();
        if (messages.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No messages yet. Write one below, or send one from the laptop page.");
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(13);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            chatList.addView(empty);
            return;
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            boolean mine = "phone".equals(item.optString("from", "laptop"));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            JSONObject attachment = item.optJSONObject("attachment");
            String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(new java.util.Date(item.optLong("time", 0)));
            TextView heading = new TextView(this);
            heading.setText((mine ? "YOU" : "LAPTOP") + " · " + time);
            heading.setTextSize(11);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setTextColor(COLOR_MUTED);
            row.addView(heading);
            String message = item.optString("text", "").trim();
            if (!message.isEmpty()) {
                TextView body = new TextView(this);
                body.setText(message);
                body.setTextSize(14);
                body.setTextColor(COLOR_INK);
                LinearLayout.LayoutParams bodyLp = matchWrap();
                bodyLp.topMargin = dp(4);
                row.addView(body, bodyLp);
                Button copyMessage = button("Copy", COLOR_SOFT);
                copyMessage.setTextColor(COLOR_INK);
                copyMessage.setTextSize(12);
                copyMessage.setMinHeight(dp(38));
                copyMessage.setOnClickListener(v -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("PocketBridge message", message));
                        Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Could not copy this message", Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams copyLp = matchWrap();
                copyLp.topMargin = dp(4);
                row.addView(copyMessage, copyLp);
            }
            if (attachment != null) {
                String name = attachment.optString("name", "Shared file");
                String mime = attachment.optString("mime", "application/octet-stream");
                String kind = mime.startsWith("image/") ? "PHOTO" : mime.startsWith("video/") ? "VIDEO"
                        : mime.startsWith("audio/") ? "AUDIO" : (name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? "PDF" : "FILE");
                LinearLayout fileCard = new LinearLayout(this);
                fileCard.setOrientation(LinearLayout.VERTICAL);
                fileCard.setPadding(dp(10), dp(9), dp(10), dp(9));
                android.graphics.drawable.GradientDrawable fileBg = new android.graphics.drawable.GradientDrawable();
                fileBg.setColor(COLOR_SURFACE);
                fileBg.setCornerRadius(dp(10));
                fileBg.setStroke(dp(1), COLOR_LINE);
                fileCard.setBackground(fileBg);
                TextView fileKind = new TextView(this);
                fileKind.setText("▣  " + kind + "  ·  " + android.text.format.Formatter.formatFileSize(this, attachment.optLong("size", 0)));
                fileKind.setTextSize(11);
                fileKind.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                fileKind.setTextColor(COLOR_GREEN);
                fileCard.addView(fileKind);
                TextView fileName = new TextView(this);
                fileName.setText(name);
                fileName.setTextSize(14);
                fileName.setTextColor(COLOR_INK);
                fileName.setMaxLines(2);
                LinearLayout.LayoutParams fileNameLp = matchWrap();
                fileNameLp.topMargin = dp(3);
                fileCard.addView(fileName, fileNameLp);
                TextView action = new TextView(this);
                action.setText(mime.startsWith("video/") ? "Tap to choose a player  ›" : "Tap to open  ›");
                action.setTextSize(12);
                action.setTextColor(COLOR_MUTED);
                LinearLayout.LayoutParams actionLp = matchWrap();
                actionLp.topMargin = dp(3);
                fileCard.addView(action, actionLp);
                Button saveFile = button("Download to phone", COLOR_SOFT);
                saveFile.setTextColor(COLOR_INK);
                saveFile.setTextSize(12);
                saveFile.setMinHeight(dp(40));
                LinearLayout.LayoutParams saveLp = matchWrap();
                saveLp.topMargin = dp(5);
                fileCard.addView(saveFile, saveLp);
                saveFile.setOnClickListener(v -> requestSaveChatAttachment(attachment));
                LinearLayout.LayoutParams fileLp = matchWrap();
                fileLp.topMargin = dp(7);
                row.addView(fileCard, fileLp);
                fileCard.setOnClickListener(v -> openChatAttachment(attachment));
            }
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
            bubble.setCornerRadius(dp(12));
            bubble.setColor(mine ? COLOR_SOFT : COLOR_SURFACE);
            row.setBackground(bubble);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(6);
            lp.leftMargin = mine ? dp(36) : 0;
            lp.rightMargin = mine ? 0 : dp(36);
            chatList.addView(row, lp);
        }
        if (chatMessageScroll != null) {
            if (chatVisible) chatMessageScroll.post(() -> chatMessageScroll.scrollTo(0,
                    wasNearBottom ? chatList.getBottom() : Math.min(previousScroll, Math.max(0, chatList.getBottom() - chatMessageScroll.getHeight()))));
            else chatNeedsBottomScroll = true;
        }
        if (showIncomingFileAlert && !isFinishing()) {
            JSONObject attachment = newestLaptopAttachment;
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(newLaptopFileNames.size(), 4); i++) {
                if (i > 0) names.append("\n");
                names.append("• ").append(newLaptopFileNames.get(i));
            }
            if (newLaptopFileNames.size() > 4) names.append("\n… and ").append(newLaptopFileNames.size() - 4).append(" more");
            new android.app.AlertDialog.Builder(this)
                    .setTitle(newLaptopFileNames.size() == 1 ? "File received in chat" : newLaptopFileNames.size() + " files received in chat")
                    .setMessage("From the laptop:\n" + names + "\n\nChoose a file in the chat to open it, or tap Download to phone to save a copy.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("View chat", null)
                    .show();
        }
    }

    private void requestSaveChatAttachment(JSONObject attachment) {
        String id = attachment.optString("id", "");
        if (!id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals("..")) {
            Toast.makeText(this, "This chat file has an invalid local name.", Toast.LENGTH_LONG).show();
            return;
        }
        java.io.File inbox = new java.io.File(getFilesDir(), "received");
        java.io.File source = new java.io.File(inbox, id);
        try {
            if (!source.getCanonicalFile().getParentFile().equals(inbox.getCanonicalFile()) || !source.isFile()) {
                throw new java.io.IOException("The received file is no longer available.");
            }
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        pendingChatSaveSource = source;
        pendingChatSaveName = attachment.optString("name", source.getName());
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(valueOr(attachment.optString("mime", ""), "application/octet-stream"));
        create.putExtra(Intent.EXTRA_TITLE, pendingChatSaveName);
        try { startActivityForResult(create, REQUEST_SAVE_CHAT_ATTACHMENT); }
        catch (Exception error) {
            pendingChatSaveSource = null;
            pendingChatSaveName = null;
            Toast.makeText(this, "Could not open the save location picker.", Toast.LENGTH_LONG).show();
        }
    }

    private void saveChatAttachment(android.net.Uri destination) {
        java.io.File source = pendingChatSaveSource;
        String name = pendingChatSaveName;
        pendingChatSaveSource = null;
        pendingChatSaveName = null;
        if (source == null) return;
        background.execute(() -> {
            try (java.io.InputStream input = new java.io.FileInputStream(source);
                 java.io.OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new java.io.IOException("Android did not open the selected destination.");
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
                runOnUiThread(() -> Toast.makeText(this, "Saved " + name, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                String message = error.getMessage() == null ? "Could not save the received file." : error.getMessage();
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void choosePhoneFileForLaptop() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(picker, REQUEST_SEND_FILE); }
        catch (Exception error) { Toast.makeText(this, "Could not open the file picker.", Toast.LENGTH_SHORT).show(); }
    }

    private String randomQrId() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private void chooseQrFile() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(picker, REQUEST_QR_SEND_FILE); }
        catch (Exception error) { Toast.makeText(this, "Could not open the file picker.", Toast.LENGTH_SHORT).show(); }
    }

    private void showQrFrames(List<String> frames, String title) {
        if (frames == null || frames.isEmpty()) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(12));
        layout.setBackgroundColor(COLOR_SURFACE);
        TextView heading = new TextView(this);
        heading.setText(title + " · scan every frame");
        heading.setTextColor(COLOR_INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextSize(17);
        layout.addView(heading, matchWrap());
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(-1, dp(300));
        imageLp.topMargin = dp(10);
        layout.addView(image, imageLp);
        TextView progress = new TextView(this);
        progress.setTextColor(COLOR_MUTED);
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(0, dp(6), 0, dp(6));
        layout.addView(progress, matchWrap());
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button("‹ Previous", COLOR_SOFT);
        previous.setTextColor(COLOR_INK);
        Button next = button("Next ›", COLOR_GREEN);
        Button close = button("Close", COLOR_SOFT);
        close.setTextColor(COLOR_INK);
        controls.addView(previous, new LinearLayout.LayoutParams(0, -2, 1f));
        controls.addView(next, new LinearLayout.LayoutParams(0, -2, 1f));
        controls.addView(close, new LinearLayout.LayoutParams(0, -2, 1f));
        layout.addView(controls, matchWrap());
        dialog.setContentView(layout);
        dialog.setCancelable(true);
        final int[] selected = {0};
        Runnable render = () -> {
            if (!dialog.isShowing()) return;
            String payload = frames.get(selected[0]);
            try {
                java.util.EnumMap<com.google.zxing.EncodeHintType, Object> hints = new java.util.EnumMap<>(com.google.zxing.EncodeHintType.class);
                hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
                hints.put(com.google.zxing.EncodeHintType.MARGIN, 2);
                com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(
                        payload, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640, hints);
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                image.setImageBitmap(bitmap);
                progress.setText("Frame " + (selected[0] + 1) + " of " + frames.size() + " · scan all frames in order");
            } catch (Exception error) {
                progress.setText("Could not render this QR: " + error.getMessage());
            }
        };
        previous.setOnClickListener(v -> { selected[0] = (selected[0] + frames.size() - 1) % frames.size(); render.run(); });
        next.setOnClickListener(v -> { selected[0] = (selected[0] + 1) % frames.size(); render.run(); });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f), -2);
        render.run();
        if (frames.size() > 1) {
            Runnable advance = new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing()) return;
                    selected[0] = (selected[0] + 1) % frames.size();
                    render.run();
                    qrHandler.postDelayed(this, 900);
                }
            };
            qrHandler.postDelayed(advance, 900);
            dialog.setOnDismissListener(ignored -> qrHandler.removeCallbacks(advance));
        }
    }

    private void scanQrFromCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_QR_CAMERA_PERMISSION);
            return;
        }
        if (qrScannerDialog != null && qrScannerDialog.isShowing()) return;
        seenQrFrames.clear();
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar);
        qrScannerDialog = dialog;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(COLOR_BG);
        layout.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = new TextView(this);
        title.setText("Scan QR transfer");
        title.setTextColor(COLOR_INK);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title, matchWrap());
        qrScannerStatus = new TextView(this);
        qrScannerStatus.setText("Hold the phone steady. All QR frames are read automatically.");
        qrScannerStatus.setTextColor(COLOR_MUTED);
        qrScannerStatus.setPadding(0, dp(8), 0, dp(8));
        layout.addView(qrScannerStatus, matchWrap());
        qrScannerView = new com.journeyapps.barcodescanner.DecoratedBarcodeView(this);
        qrScannerView.getBarcodeView().setDecoderFactory(new com.journeyapps.barcodescanner.DefaultDecoderFactory(
                java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE)));
        layout.addView(qrScannerView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button close = button("Stop scanning", COLOR_SOFT);
        close.setTextColor(COLOR_INK);
        layout.addView(close, matchWrap());
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(layout);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(ignored -> {
            if (qrScannerView != null) qrScannerView.pause();
            qrScannerView = null;
            qrScannerStatus = null;
            qrScannerDialog = null;
            seenQrFrames.clear();
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setLayout(-1, -1);
        qrScannerView.decodeContinuous(new com.journeyapps.barcodescanner.BarcodeCallback() {
            @Override public void barcodeResult(com.journeyapps.barcodescanner.BarcodeResult result) {
                if (result == null || result.getText() == null || !seenQrFrames.add(result.getText())) return;
                acceptQrPayload(result.getText());
            }
            @Override public void possibleResultPoints(java.util.List<com.google.zxing.ResultPoint> points) { }
        });
        qrScannerView.resume();
    }

    private void closeQrScanner() {
        if (qrScannerDialog != null && qrScannerDialog.isShowing()) qrScannerDialog.dismiss();
    }

    private void acceptQrPayload(String payload) {
        QrTransferCodec.Frame frame = QrTransferCodec.parse(payload);
        if (frame == null) {
            closeQrScanner();
            new android.app.AlertDialog.Builder(this).setTitle("QR text")
                    .setMessage(payload).setNegativeButton("Close", null)
                    .setPositiveButton("Copy", (d, w) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("QR text", payload));
                    }).show();
            return;
        }
        if (qrAssemblies.size() >= 6 && !qrAssemblies.containsKey(frame.id)) qrAssemblies.clear();
        QrAssembly assembly = qrAssemblies.get(frame.id);
        if (assembly == null) {
            assembly = new QrAssembly(frame);
            qrAssemblies.put(frame.id, assembly);
        }
        if (assembly.text != frame.text || assembly.chunks.length != frame.total
                || (!frame.text && (!assembly.name.equals(frame.name) || !assembly.mime.equals(frame.mime)))) {
            qrAssemblies.remove(frame.id);
            closeQrScanner();
            Toast.makeText(this, "QR frames do not belong to the same transfer. Start again from the first frame.", Toast.LENGTH_LONG).show();
            return;
        }
        if (assembly.chunks[frame.index] == null) {
            assembly.chunks[frame.index] = frame.bytes;
            assembly.received++;
        }
        if (assembly.received < assembly.chunks.length) {
            String message = "QR transfer " + assembly.received + "/" + assembly.chunks.length + " frames received. Keep scanning.";
            if (qrScannerStatus != null) qrScannerStatus.setText(message);
            else Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }
        closeQrScanner();
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        try {
            for (byte[] chunk : assembly.chunks) joined.write(chunk);
        } catch (java.io.IOException impossible) { }
        qrAssemblies.remove(frame.id);
        byte[] data = joined.toByteArray();
        if (data.length > QrTransferCodec.MAX_BYTES) {
            Toast.makeText(this, "This QR transfer is larger than the 128 KiB limit. Use Wi-Fi or Hotspot.", Toast.LENGTH_LONG).show();
            return;
        }
        if (assembly.text) {
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            new android.app.AlertDialog.Builder(this).setTitle("Text received by QR")
                    .setMessage(text).setNegativeButton("Close", null)
                    .setPositiveButton("Copy", (d, w) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("QR text", text));
                        Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
                    }).show();
            return;
        }
        pendingQrFile = data;
        pendingQrName = assembly.name.isEmpty() ? "PocketBridge-QR-file" : assembly.name;
        pendingQrMime = assembly.mime.isEmpty() ? "application/octet-stream" : assembly.mime;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(pendingQrMime);
        create.putExtra(Intent.EXTRA_TITLE, pendingQrName);
        try { startActivityForResult(create, REQUEST_QR_RECEIVE_SAVE); }
        catch (Exception error) { Toast.makeText(this, "Could not open the save location picker.", Toast.LENGTH_LONG).show(); }
    }


    private void openChatAttachment(JSONObject attachment) {
        String id = attachment.optString("id", "");
        String code = codeText == null ? "" : codeText.getText().toString().replaceAll("\\s", "");
        String url = "http://127.0.0.1:" + MediaHttpServer.PORT + "/api/inbox/"
                + android.net.Uri.encode(id) + "?code=" + android.net.Uri.encode(code);
        String mime = attachment.optString("mime", "application/octet-stream");
        if (mime.startsWith("video/")) {
            openVideoWithChoice(android.net.Uri.parse(url), mime, 0);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(android.net.Uri.parse(url), mime);
        try { startActivity(Intent.createChooser(view, "Open with")); }
        catch (Exception typed) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (Exception ignored) { Toast.makeText(this, "Could not open the shared file.", Toast.LENGTH_SHORT).show(); }
        }
    }

    /** Always show Android's resolver so the user can choose any compatible installed player. */
    private void openVideoWithChoice(android.net.Uri uri, String mime, int grantFlags) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(grantFlags);
        try { startActivity(Intent.createChooser(intent, "Play with")); }
        catch (Exception error) { Toast.makeText(this, "No compatible video player is installed.", Toast.LENGTH_SHORT).show(); }
    }

    private void addCard(LinearLayout parent, String label, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackgroundColor(COLOR_SURFACE);
        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextColor(COLOR_MUTED);
        heading.setTextSize(11);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, 0, 0, dp(10));
        card.addView(heading);
        card.addView(content);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        parent.addView(card, lp);
    }

    private Button button(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        button.setMinHeight(dp(52));
        return button;
    }

    private void ensurePermissionsAndStart(String action) {
        List<String> required = new ArrayList<>();
        // Android 13+ uses Nearby Wi-Fi access; older versions use location
        // access to show the connected Wi-Fi name and manage a local hotspot.
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(required, Manifest.permission.NEARBY_WIFI_DEVICES);
        else {
            addIfMissing(required, Manifest.permission.ACCESS_FINE_LOCATION);
            addIfMissing(required, Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 14+ can grant access to selected photos only. Do not keep
            // asking for full library access once that partial access is granted.
            boolean partialMedia = Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            if (!partialMedia) {
                addIfMissing(required, Manifest.permission.READ_MEDIA_IMAGES);
                addIfMissing(required, Manifest.permission.READ_MEDIA_VIDEO);
                if (Build.VERSION.SDK_INT >= 34) addIfMissing(required, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            }
            addIfMissing(required, Manifest.permission.READ_MEDIA_AUDIO);
            addIfMissing(required, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addIfMissing(required, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        pendingAction = action;
        if (!required.isEmpty()) requestPermissions(required.toArray(new String[0]), REQUEST_PERMISSIONS);
        else sendServiceCommand(action);
    }

    private String pendingAction = ShareSessionService.ACTION_START_HOTSPOT;

    private void addIfMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) permissions.add(permission);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_QR_CAMERA_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanQrFromCamera();
            else Toast.makeText(this, "Allow camera access to scan QR transfers.", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCameraSession();
            else if (cameraStatus != null) cameraStatus.setText("Camera permission was not granted. You can enable it in Android Settings when you want to share.");
            return;
        }
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean wifiPermissionAllowed = Build.VERSION.SDK_INT >= 33
                    ? checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                    : checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean needsHotspot = ShareSessionService.ACTION_START_HOTSPOT.equals(pendingAction);
            boolean allowed = !needsHotspot || wifiPermissionAllowed;
            if (allowed) sendServiceCommand(pendingAction);
            else statusText.setText("Allow Nearby Wi-Fi access to start the phone hotspot. You can still use Wi-Fi mode.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_CHAT_ATTACHMENT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) saveChatAttachment(data.getData());
            else { pendingChatSaveSource = null; pendingChatSaveName = null; }
            return;
        }
        if (requestCode == REQUEST_QR_SEND_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) prepareQrFile(data.getData());
            return;
        }
        if (requestCode == REQUEST_QR_RECEIVE_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingQrFile != null) {
                try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData(), "w")) {
                    if (output == null) throw new java.io.IOException("Android did not open the selected destination.");
                    output.write(pendingQrFile);
                    Toast.makeText(this, "Saved " + pendingQrName, Toast.LENGTH_LONG).show();
                } catch (Exception error) {
                    Toast.makeText(this, "Could not save the QR file: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingQrFile = null;
            pendingQrName = null;
            pendingQrMime = null;
            return;
        }
        if (requestCode == REQUEST_SEND_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                List<android.net.Uri> selected = new ArrayList<>();
                android.content.ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        android.net.Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) selected.add(uri);
                    }
                } else if (data.getData() != null) selected.add(data.getData());
                for (android.net.Uri uri : selected) savePhoneFileForLaptop(uri);
                if (selected.size() > 1) Toast.makeText(this, "Queued " + selected.size() + " files in order.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, ScreenCaptureService.class);
                service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
                statusText.setText("Screen capture approved. Starting screen encoder…");
                refreshScreenButton();
                screenStatusHandler.removeCallbacks(screenCaptureStatusPoll);
                screenStatusHandler.postDelayed(screenCaptureStatusPoll, 300);
                Toast.makeText(this, "Keep PocketBridge open. Choose Watch screen on the laptop.", Toast.LENGTH_LONG).show();
            } else Toast.makeText(this, "Screen sharing was cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    private void prepareQrFile(android.net.Uri uri) {
        String name = "PocketBridge-QR-file";
        String mime = getContentResolver().getType(uri);
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0 && cursor.getString(nameColumn) != null) name = cursor.getString(nameColumn);
            }
        } catch (Exception ignored) { }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new java.io.IOException("Android could not open this file.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > QrTransferCodec.MAX_BYTES) {
                    throw new java.io.IOException("QR Share is limited to 128 KiB. Use Wi-Fi or Hotspot for larger photos and videos.");
                }
                bytes.write(buffer, 0, count);
            }
            List<String> frames = QrTransferCodec.fileFrames(randomQrId(), name,
                    valueOr(mime, "application/octet-stream"), bytes.toByteArray());
            showQrFrames(frames, name);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage() == null ? "Could not read the selected file." : error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void savePhoneFileForLaptop(android.net.Uri uri) {
        phoneUploadQueue.execute(() -> {
            java.io.File target = null;
            try {
                String name = "shared-file";
                long expected = -1;
                try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        int sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (nameColumn >= 0) name = cursor.getString(nameColumn);
                        if (sizeColumn >= 0) expected = cursor.getLong(sizeColumn);
                    }
                }
                String safeName = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
                if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) safeName = "shared-file";
                java.io.File inbox = new java.io.File(getFilesDir(), "received");
                if (!inbox.exists() && !inbox.mkdirs()) throw new java.io.IOException("Could not create the receive folder.");
                if (expected > 0 && inbox.getUsableSpace() < expected) throw new java.io.IOException("The phone does not have enough free storage for this file.");
                target = new java.io.File(inbox, System.currentTimeMillis() + "-" + safeName);
                long copied = 0;
                try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                    if (input == null) throw new java.io.IOException("Could not open the selected file.");
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        copied += read;
                        if (inbox.getUsableSpace() < read) throw new java.io.IOException("The phone ran out of storage while copying.");
                        output.write(buffer, 0, read);
                        if (expected >= copied && inbox.getUsableSpace() < expected - copied) {
                            throw new java.io.IOException("The phone ran out of free storage while copying.");
                        }
                    }
                    output.getFD().sync();
                }
                ChatStore.appendFile(this, safeName, target.getName(), copied, "phone");
                runOnUiThread(() -> {
                    Toast.makeText(this, "File is ready in the shared inbox.", Toast.LENGTH_LONG).show();
                    showTab(3);
                });
            } catch (Exception error) {
                if (target != null) { /*noinspection ResultOfMethodCallIgnored*/ target.delete(); }
                String message = error.getMessage() == null ? "Could not share this file." : error.getMessage();
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendServiceCommand(String action) {
        Intent service = new Intent(this, ShareSessionService.class).setAction(action);
        boolean foregroundStart = ShareSessionService.ACTION_START.equals(action)
                || ShareSessionService.ACTION_START_WIFI.equals(action)
                || ShareSessionService.ACTION_START_HOTSPOT.equals(action);
        if (Build.VERSION.SDK_INT >= 26 && foregroundStart) startForegroundService(service);
        else startService(service);
    }

    /** Pulls just the host out of http://host:port so the address is easy to read. */
    private String addressFrom(String url) {
        if (url == null || !url.startsWith("http://")) return "";
        String rest = url.substring("http://".length());
        int colon = rest.indexOf(':');
        return colon > 0 ? rest.substring(0, colon) : rest;
    }

    private String valueOr(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
}
