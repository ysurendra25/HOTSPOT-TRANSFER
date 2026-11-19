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
import android.view.ViewParent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_SCREEN_CAPTURE = 41;
    private static final int REQUEST_SEND_FILE = 42;
    private static final int REQUEST_QR_SEND_FILE = 43;
    private static final int REQUEST_QR_RECEIVE_SAVE = 45;
    private static final int REQUEST_QR_CAMERA_PERMISSION = 46;
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
    private Button laptopLoadButton;
    private boolean syncingHelperLinks;
    private Button[] laptopKindButtons;
    private LinearLayout laptopLibrarySection;
    private LinearLayout laptopRowHolder;
    private TextView laptopStatus;
    private TextView laptopScreenStatus;
    private TextView laptopScreenViewerStatus;
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
    private android.webkit.WebView laptopScreenView;
    private ViewGroup laptopScreenParent;
    private ViewGroup.LayoutParams laptopScreenOriginalLayout;
    private int laptopScreenOriginalIndex = -1;
    private android.app.Dialog laptopScreenDialog;
    private boolean receiverRegistered;
    private boolean sharing;

    private static final class QrAssembly {
        final boolean text;
        final String name;
        final String mime;
        final byte[][] chunks;
        int received;
        QrAssembly(QrTransferCodec.Frame frame) {
            text = frame.text; name = frame.name; mime = frame.mime; chunks = new byte[frame.total][];
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
            wifiButton.setEnabled(!sharing);
            hotspotButton.setEnabled(!sharing);
            stopButton.setEnabled(sharing);
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
    }

    /** The screen button doubles as the way to stop screen sharing while it runs. */
    private void refreshScreenButton() {
        if (screenButton != null) {
            screenButton.setText(ScreenCaptureService.isRunning() ? "Stop screen share" : "Share phone screen");
        }
    }

    @Override protected void onStop() {
        chatHandler.removeCallbacks(chatPoll);
        closeQrScanner();
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

        // Four tabs: Connect, Screen, Library, Chat. Each one gets its own
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
        ScrollView cloneScroll = new ScrollView(this);
        LinearLayout cloneContent = tabColumn();
        cloneScroll.addView(cloneContent);
        tabHost.addView(connectScroll);
        tabHost.addView(screenScroll);
        tabHost.addView(libraryScroll);
        tabHost.addView(chatScroll);
        tabHost.addView(cloneScroll);
        screenScroll.setVisibility(View.GONE);
        libraryScroll.setVisibility(View.GONE);
        chatScroll.setVisibility(View.GONE);
        cloneScroll.setVisibility(View.GONE);
        this.tabViews = new ScrollView[]{connectScroll, screenScroll, libraryScroll, chatScroll, cloneScroll};

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
        qrIntro.setText("QR Share uses only the screen and camera; the phone and laptop do not connect by Wi-Fi, hotspot, Bluetooth, or internet. Large files are too slow for QR, so this path is capped at 128 KiB.");
        qrIntro.setTextColor(COLOR_MUTED);
        qrIntro.setTextSize(13);
        qrIntro.setLineSpacing(dp(3), 1f);
        qrCard.addView(qrIntro, matchWrap());
        qrTextInput = new EditText(this);
        qrTextInput.setHint("Text to show as QR frames");
        qrTextInput.setMinLines(2);
        qrTextInput.setMaxLines(4);
        qrCard.addView(qrTextInput, matchWrap());
        Button textQr = button("Display text as QR", COLOR_GREEN);
        textQr.setOnClickListener(v -> {
            String text = qrTextInput.getText().toString();
            if (text.isEmpty()) { Toast.makeText(this, "Enter text to share.", Toast.LENGTH_SHORT).show(); return; }
            try { showQrFrames(QrTransferCodec.textFrames(randomQrId(), text), "Text"); }
            catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        qrCard.addView(textQr, matchWrap());
        Button fileQr = button("Choose a small file for QR Share", COLOR_SOFT);
        fileQr.setTextColor(COLOR_INK);
        fileQr.setOnClickListener(v -> chooseQrFile());
        qrCard.addView(fileQr, matchWrap());
        Button scanQr = button("Scan QR from laptop", COLOR_SOFT);
        scanQr.setTextColor(COLOR_INK);
        scanQr.setOnClickListener(v -> scanQrFromCamera());
        qrCard.addView(scanQr, matchWrap());
        TextView qrHelp = new TextView(this);
        qrHelp.setText("For laptop ↔ phone QR transfer, open the Windows helper’s local page on the laptop: http://127.0.0.1:7070/qr.html?t=<helper code>. It uses the laptop camera to receive phone QR frames and can display file/text QR frames for this phone to scan.");
        qrHelp.setTextColor(COLOR_MUTED);
        qrHelp.setTextSize(12);
        qrHelp.setPadding(0, dp(8), 0, 0);
        qrCard.addView(qrHelp, matchWrap());
        addCard(content, "QR SHARE · CAMERA ONLY", qrCard);

        screenHelperLink = new EditText(this);
        screenHelperLink.setSingleLine(true);
        screenHelperLink.setHint("http://laptop-address:7070/?t=code");
        screenHelperLink.setTextSize(14);
        screenHelperLink.setText(getSavedHelperLink());
        MediaHttpServer.setLaptopHelperLink(getSavedHelperLink());
        watchHelperLink(screenHelperLink);
        addCard(content, "WINDOWS HELPER LINK · PASTE ONCE", screenHelperLink);
        laptopScreenStatus = new TextView(this);
        laptopScreenStatus.setText("Start the portable Windows helper, then paste its local link here. Keep the laptop awake for screen sharing.");
        laptopScreenStatus.setTextColor(COLOR_MUTED);
        laptopScreenStatus.setTextSize(13);
        laptopScreenStatus.setLineSpacing(dp(3), 1f);
        content.addView(laptopScreenStatus, matchWrap());

        LinearLayout powerControls = new LinearLayout(this);
        powerControls.setOrientation(LinearLayout.VERTICAL);
        String[][] powerActions = {{"Lock laptop", "lock"}, {"Wake display", "wake-display"},
                {"Show sign-in screen", "unlock"}, {"Put laptop to sleep", "sleep"}, {"Shut down laptop", "shutdown"}};
        for (String[] powerAction : powerActions) {
            Button actionButton = button(powerAction[0], ("shutdown".equals(powerAction[1]) || "sleep".equals(powerAction[1])) ? COLOR_SOFT : COLOR_GREEN);
            if ("shutdown".equals(powerAction[1]) || "sleep".equals(powerAction[1])) actionButton.setTextColor(COLOR_INK);
            actionButton.setOnClickListener(v -> {
                String action = powerAction[1];
                if ("shutdown".equals(action) || "sleep".equals(action)) {
                    boolean shutdown = "shutdown".equals(action);
                    new android.app.AlertDialog.Builder(this).setTitle(shutdown ? "Shut down laptop" : "Put laptop to sleep")
                            .setMessage(shutdown ? "Schedule Windows shutdown in 15 seconds? The hotspot connection will end."
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
        powerNote.setText("Windows cannot be remotely unlocked; Show sign-in wakes the panel and leaves PIN/password entry on the laptop. Wake display works only while the helper is running and the laptop is awake. Wi-Fi cannot wake a fully sleeping or shut-down PC unless Wake-on-LAN is separately enabled in its firmware and network adapter.");
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
            if (ScreenCaptureService.isRunning()) {
                stopService(new Intent(this, ScreenCaptureService.class));
                statusText.setText("Screen sharing stopped. Files are still shared.");
                screenButton.setText("Share phone screen");
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
        laptopScreenView = laptopView;
        laptopView.getSettings().setJavaScriptEnabled(true);
        laptopView.getSettings().setDomStorageEnabled(true);
        laptopView.getSettings().setLoadWithOverviewMode(true);
        laptopView.getSettings().setUseWideViewPort(true);
        laptopView.setBackgroundColor(Color.BLACK);
        laptopView.setVisibility(View.GONE);
        laptopView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        laptopView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(380)));
        final android.os.Handler screenStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] screenStatusPoll = new Runnable[1];
        screenStatusPoll[0] = () -> {
            if (laptopView.getVisibility() != View.VISIBLE) return;
            laptopView.evaluateJavascript("(function(){var d=document.getElementById('dot');var v=document.getElementById('screenv');var i=document.getElementById('screenj');var s=d?d.className:'offline';var w=v&&v.videoWidth?v.videoWidth:(i?i.naturalWidth:0);var h=v&&v.videoHeight?v.videoHeight:(i?i.naturalHeight:0);return s+'|'+w+'x'+h})()", result -> {
                String state = result == null ? "" : result.replace("\\\"", "").replace("\"", "");
                if (state.startsWith("live|")) setLaptopViewerStatus("Laptop screen live · " + state.substring(5));
                else if (state.startsWith("connecting|")) setLaptopViewerStatus("Connecting to the laptop screen…");
                else if (state.startsWith("offline|")) setLaptopViewerStatus("No screen frames received. Confirm Windows is awake, the helper is running, and the helper link is current.");
            });
            screenStatusHandler.postDelayed(screenStatusPoll[0], 1000);
        };
        laptopView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView view, String url) {
                setLaptopViewerStatus("Viewer loaded · waiting for laptop frames…");
                screenStatusHandler.removeCallbacks(screenStatusPoll[0]);
                screenStatusHandler.post(screenStatusPoll[0]);
            }
            @Override public void onReceivedError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) setLaptopViewerStatus("Could not reach the laptop helper. Keep both devices on the same hotspot and confirm Windows Firewall allows PocketBridge on private networks.");
            }
            @Override public void onReceivedHttpError(android.webkit.WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) setLaptopViewerStatus(response.getStatusCode() == 401
                        ? "The helper link was rejected. Copy a fresh link from the running Windows helper and paste it again."
                        : "The laptop helper returned HTTP " + response.getStatusCode() + ". Check the helper and try again.");
            }
        });
        Button showLaptop = button("Show laptop screen here", COLOR_GREEN);
        showLaptop.setOnClickListener(v -> {
            String link = screenHelperLink.getText().toString().trim();
            if (link.isEmpty()) {
                setLaptopViewerStatus("Start PocketBridge Windows Control on the laptop, then paste its printed link on Connect.");
                return;
            }
            setLaptopViewerStatus("Checking the laptop link…");
            new Thread(() -> {
                try {
                    android.net.Uri parsed = android.net.Uri.parse(link);
                    String token = parsed.getQueryParameter("t");
                    if (token == null || token.isEmpty() || !"http".equalsIgnoreCase(parsed.getScheme())) {
                        throw new Exception("Paste the full local http link printed by the Windows helper, including its ?t= code.");
                    }
                    String host = parsed.getHost() == null ? "" : parsed.getHost();
                    boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
                    android.net.Uri reachable = loopback ? resolveLoopbackHelper(parsed, token, laptopScreenStatus) : parsed;
                    runOnUiThread(() -> {
                        setLaptopViewerStatus("Connecting to the laptop screen…");
                        laptopView.setVisibility(View.VISIBLE);
                        laptopView.loadUrl(reachable.toString());
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> setLaptopViewerStatus("Could not find the laptop helper: " + error.getMessage()));
                }
            }, "PocketBridge-laptop-screen-link").start();
        });
        Button hideLaptop = button("Hide laptop screen", COLOR_SOFT);
        hideLaptop.setTextColor(COLOR_INK);
        hideLaptop.setOnClickListener(v -> {
            if (laptopScreenDialog != null) laptopScreenDialog.dismiss();
            screenStatusHandler.removeCallbacks(screenStatusPoll[0]);
            laptopView.setVisibility(View.GONE);
            laptopView.loadUrl("about:blank");
            setLaptopViewerStatus("Laptop screen hidden.");
        });
        Button fullLaptop = button("Full screen", COLOR_SOFT);
        fullLaptop.setTextColor(COLOR_INK);
        fullLaptop.setOnClickListener(v -> showLaptopScreenFullscreen());
        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams liveLeft = new LinearLayout.LayoutParams(0, -2, 1f);
        liveLeft.rightMargin = dp(6);
        LinearLayout.LayoutParams liveRight = new LinearLayout.LayoutParams(0, -2, 1f);
        liveRight.leftMargin = dp(6);
        liveRow.addView(showLaptop, liveLeft);
        liveRow.addView(hideLaptop, liveRight);
        addCard(content, "LAPTOP SCREEN ON THIS PHONE", liveRow);
        content.addView(fullLaptop, matchWrap());
        laptopScreenViewerStatus = new TextView(this);
        laptopScreenViewerStatus.setText("Laptop screen is not connected yet. Paste the Windows helper link on Connect, then tap Show laptop screen here.");
        laptopScreenViewerStatus.setTextColor(COLOR_MUTED);
        laptopScreenViewerStatus.setTextSize(13);
        laptopScreenViewerStatus.setPadding(dp(4), dp(8), dp(4), dp(8));
        content.addView(laptopScreenViewerStatus, matchWrap());
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
        buildCloneTab(cloneContent);

        page.addView(tabHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
        showTab(0);
    }

    private void setLaptopViewerStatus(String message) {
        if (laptopScreenStatus != null) laptopScreenStatus.setText(message);
        if (laptopScreenViewerStatus != null) laptopScreenViewerStatus.setText(message);
    }

    /** Reparents the live laptop viewer into a real immersive, edge-to-edge dialog. */
    private void showLaptopScreenFullscreen() {
        if (laptopScreenView == null || laptopScreenView.getVisibility() != View.VISIBLE) {
            Toast.makeText(this, "Connect to the laptop screen first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (laptopScreenDialog != null && laptopScreenDialog.isShowing()) return;
        if (laptopScreenParent == null || laptopScreenView.getParent() != laptopScreenParent) {
            Toast.makeText(this, "The laptop screen viewer is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        laptopScreenParent.removeView(laptopScreenView);
        android.widget.FrameLayout fullscreen = new android.widget.FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.addView(laptopScreenView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Button close = button("Exit full screen", COLOR_SOFT);
        close.setTextColor(COLOR_INK);
        android.widget.FrameLayout.LayoutParams closeParams = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(12), dp(12), 0);
        fullscreen.addView(close, closeParams);

        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        laptopScreenDialog = dialog;
        dialog.setContentView(fullscreen);
        dialog.setCancelable(true);
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            ViewParent parent = laptopScreenView.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(laptopScreenView);
            if (laptopScreenParent != null && laptopScreenView.getParent() == null) {
                int index = laptopScreenOriginalIndex < 0 ? laptopScreenParent.getChildCount()
                        : Math.min(laptopScreenOriginalIndex, laptopScreenParent.getChildCount());
                laptopScreenParent.addView(laptopScreenView, index, laptopScreenOriginalLayout);
            }
            laptopScreenDialog = null;
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1);
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private ScrollView[] tabViews;
    private Button[] tabButtons;
    private String laptopFilterKind = "all";
    private LinearLayout chatList;
    private TextView chatStatus;
    private EditText chatInput;
    private boolean chatNotificationsPrimed;
    private long lastChatNotificationTime;
    private final android.os.Handler chatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService phoneUploadQueue = Executors.newSingleThreadExecutor();
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

    /** The bottom bar real apps use: five sections, the chosen one highlighted. */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_SURFACE);
        bar.setPadding(dp(4), dp(8), dp(4), dp(10));
        bar.setElevation(dp(8));
        int[] icons = {R.drawable.ic_tab_connect, R.drawable.ic_tab_screen, R.drawable.ic_tab_library, R.drawable.ic_tab_chat, android.R.drawable.ic_menu_manage};
        String[] labels = {"Connect", "Screen", "Library", "Chat", "Clone"};
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
        if (tabButtons != null) {
            for (int i = 0; i < tabButtons.length; i++) {
                int tint = i == index ? COLOR_GREEN : COLOR_MUTED;
                tabButtons[i].setTextColor(tint);
                tabButtons[i].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(tint));
            }
        }
        if (index == 2) loadLaptopFilesIfConfigured();
        if (index == 1 || index == 2) syncHelperLinkFields();
        if (index == 3) {
            refreshChat();
            chatHandler.removeCallbacks(chatPoll);
            chatHandler.postDelayed(chatPoll, 2500);
        } else {
            chatHandler.removeCallbacks(chatPoll);
        }
        if (index == 1) refreshScreenButton();
        if (index == 4) refreshCloneTab();
    }

    private TextView cloneStatus;
    private LinearLayout cloneApps;

    private void buildCloneTab(LinearLayout parent) {
        TextView intro = new TextView(this);
        intro.setText("Create an Android Work profile for separate app installs and accounts. This is the supported, user-controlled way to keep a second app instance on Android.");
        intro.setTextColor(COLOR_MUTED);
        intro.setTextSize(14);
        intro.setLineSpacing(dp(3), 1f);
        addCard(parent, "PRIVATE APP SPACE", intro);

        cloneStatus = new TextView(this);
        cloneStatus.setTextColor(COLOR_INK);
        cloneStatus.setTextSize(14);
        cloneStatus.setLineSpacing(dp(3), 1f);
        addCard(parent, "STATUS", cloneStatus);

        Button setup = button("Create private app space", COLOR_GREEN);
        setup.setOnClickListener(v -> startCloneProfileSetup());
        parent.addView(setup, matchWrap());
        Button open = button("Open private app space", COLOR_SOFT);
        open.setTextColor(COLOR_INK);
        open.setOnClickListener(v -> openCloneProfile());
        parent.addView(open, matchWrap());

        cloneApps = new LinearLayout(this);
        cloneApps.setOrientation(LinearLayout.VERTICAL);
        addCard(parent, "APPS AVAILABLE TO ADD", cloneApps);

        TextView limitations = new TextView(this);
        limitations.setText("PocketBridge cannot bypass Android’s app sandbox, copy private app data, or remove ads. Install a second app copy from the Work Play Store; some apps block Work profiles or are not available there. Installing the second copy requires Play Store access. Android/OEM policy may disable profile creation.");
        limitations.setTextColor(COLOR_MUTED);
        limitations.setTextSize(12);
        limitations.setPadding(dp(4), dp(12), dp(4), 0);
        parent.addView(limitations, matchWrap());
    }

    private void refreshCloneTab() {
        if (cloneStatus == null) return;
        try {
            android.app.admin.DevicePolicyManager policy = (android.app.admin.DevicePolicyManager)
                    getSystemService(DEVICE_POLICY_SERVICE);
            if (policy != null && policy.isProfileOwnerApp(getPackageName())) {
                cloneStatus.setText("This PocketBridge instance is inside the private Work profile. Use the app’s Play Store listing below to install a separate copy here.");
                loadCloneCandidatesInProfile();
                return;
            }
            if (hasCloneProfile()) {
                cloneStatus.setText("Private app space is ready. Open it, then choose apps to add.");
            } else {
                cloneStatus.setText("No private app space is set up. Android will ask you to approve a separate Work profile.");
            }
            if (cloneApps != null) {
                cloneApps.removeAllViews();
                TextView help = new TextView(this);
                help.setText("After setup, open PocketBridge from the Work profile to choose apps.");
                help.setTextColor(COLOR_MUTED);
                help.setPadding(dp(8), dp(8), dp(8), dp(8));
                cloneApps.addView(help);
            }
        } catch (Exception error) {
            cloneStatus.setText("Android did not allow the profile status to be read: " + error.getMessage());
        }
    }

    private boolean hasCloneProfile() {
        try {
            android.content.pm.LauncherApps launcher = (android.content.pm.LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
            if (launcher == null) return false;
            for (android.os.UserHandle profile : launcher.getProfiles()) {
                if (!profile.equals(android.os.Process.myUserHandle())
                        && !launcher.getActivityList(getPackageName(), profile).isEmpty()) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void startCloneProfileSetup() {
        android.app.admin.DevicePolicyManager policy = (android.app.admin.DevicePolicyManager)
                getSystemService(DEVICE_POLICY_SERVICE);
        if (policy == null || !policy.isProvisioningAllowed(android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            cloneStatus.setText("Android cannot create another Work profile on this phone. Check whether a Work profile already exists or the device administrator has disabled it.");
            return;
        }
        Intent provision = new Intent(android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
        provision.putExtra(android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                new android.content.ComponentName(this, CloneAdminReceiver.class));
        if (Build.VERSION.SDK_INT >= 33) {
            provision.putExtra(android.app.admin.DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true);
        }
        try {
            startActivityForResult(provision, REQUEST_CLONE_PROFILE);
        } catch (Exception error) {
            cloneStatus.setText("Android could not open Work profile setup: " + error.getMessage());
        }
    }

    private static final int REQUEST_CLONE_PROFILE = 44;

    private void openCloneProfile() {
        try {
            android.content.pm.LauncherApps launcher = (android.content.pm.LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
            if (launcher == null) throw new IllegalStateException("Android app launcher is unavailable.");
            for (android.os.UserHandle profile : launcher.getProfiles()) {
                if (profile.equals(android.os.Process.myUserHandle())) continue;
                java.util.List<android.content.pm.LauncherActivityInfo> activities = launcher.getActivityList(getPackageName(), profile);
                if (!activities.isEmpty()) {
                    launcher.startMainActivity(activities.get(0).getComponentName(), profile, null, null);
                    return;
                }
            }
            cloneStatus.setText("The private profile’s PocketBridge copy is not available yet. Finish Android’s Work profile setup, then try again.");
        } catch (Exception error) {
            cloneStatus.setText("Could not open the private app space: " + error.getMessage());
        }
    }

    private void loadCloneCandidatesInProfile() {
        if (cloneApps == null) return;
        cloneApps.removeAllViews();
        TextView instruction = new TextView(this);
        instruction.setText("Enter the app’s package name (for example, com.example.app). Play Store installs its second instance inside this Work profile.");
        instruction.setTextColor(COLOR_MUTED);
        instruction.setPadding(dp(8), dp(8), dp(8), dp(8));
        cloneApps.addView(instruction, matchWrap());
        EditText packageField = new EditText(this);
        packageField.setSingleLine(true);
        packageField.setHint("App package name");
        cloneApps.addView(packageField, matchWrap());
        Button openStore = button("Open app in Work Play Store", COLOR_SOFT);
        openStore.setTextColor(COLOR_INK);
        openStore.setOnClickListener(v -> {
            String packageName = packageField.getText().toString().trim();
            if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                Toast.makeText(this, "Enter a valid app package name.", Toast.LENGTH_SHORT).show();
                return;
            }
            android.net.Uri listing = android.net.Uri.parse("market://details?id=" + android.net.Uri.encode(packageName));
            try { startActivity(new Intent(Intent.ACTION_VIEW, listing).setPackage("com.android.vending")); }
            catch (Exception error) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + android.net.Uri.encode(packageName)))); }
                catch (Exception unavailable) { Toast.makeText(this, "The Work profile Play Store is not available on this phone.", Toast.LENGTH_LONG).show(); }
            }
        });
        cloneApps.addView(openStore, matchWrap());
        Button browseStore = button("Browse Work Play Store", COLOR_SOFT);
        browseStore.setTextColor(COLOR_INK);
        browseStore.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q="))
                    .setPackage("com.android.vending")); }
            catch (Exception error) { Toast.makeText(this, "The Work profile Play Store is not available on this phone.", Toast.LENGTH_LONG).show(); }
        });
        cloneApps.addView(browseStore, matchWrap());
    }

    /** The library on the phone is dedicated to files shared by the laptop. */
    private void buildLibraryTab(LinearLayout parent) {
        laptopLibrarySection = new LinearLayout(this);
        laptopLibrarySection.setOrientation(LinearLayout.VERTICAL);
        parent.addView(laptopLibrarySection, matchWrap());

        TextView laptopIntro = new TextView(this);
        laptopIntro.setText("Browse the laptop from this phone. Leave the path empty to search your Windows user folders, or paste a folder or file path. Searching happens on the laptop and returns matching files only.");
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
        laptopStatusView.setText("Paste the Windows helper link on Connect. Empty path searches the Windows user folders; use an exact path to narrow access to one folder.");
        laptopStatusView.setTextColor(COLOR_MUTED);
        laptopStatusView.setTextSize(12);
        laptopStatusView.setPadding(dp(4), 0, dp(4), dp(10));
        laptopLibrarySection.addView(laptopStatusView);

        LinearLayout laptopRows = new LinearLayout(this);
        laptopRows.setOrientation(LinearLayout.VERTICAL);
        addCard(laptopLibrarySection, "LAPTOP FILES", laptopRows);

        Button loadLaptop = button("Load laptop files", COLOR_GREEN);
        laptopLoadButton = loadLaptop;
        loadLaptop.setOnClickListener(v -> loadLaptopFiles());
        LinearLayout.LayoutParams loadGap = matchWrap();
        loadGap.topMargin = dp(2);
        laptopLibrarySection.addView(loadLaptop, loadGap);
        this.laptopFilter = laptopSearchField;
        this.laptopRowHolder = laptopRows;
        this.laptopStatus = laptopStatusView;
        laptopSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterLaptopRows(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
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

    /** Hides laptop rows that do not match the search box. */
    private void filterLaptopRows() {
        if (laptopRowHolder == null || laptopFilter == null) return;
        String query = laptopFilter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < laptopRowHolder.getChildCount(); i++) {
            View row = laptopRowHolder.getChildAt(i);
            String[] meta = row.getTag() instanceof String[] ? (String[]) row.getTag() : new String[]{"", "", ""};
            String name = meta[0];
            String mime = meta[1];
            String searchable = (name + " " + meta[2] + " " + mime).toLowerCase(java.util.Locale.ROOT);
            boolean typeMatches = "all".equals(laptopFilterKind)
                    || ("other".equals(laptopFilterKind)
                        ? !(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))
                        : mime.startsWith(laptopFilterKind + "/"));
            boolean matches = typeMatches && (query.isEmpty() || searchable.contains(query));
            row.setVisibility(matches ? View.VISIBLE : View.GONE);
            if (matches) shown++;
        }
        if (laptopStatus != null && laptopRowHolder.getChildCount() > 0) {
            laptopStatus.setText(shown + " of " + laptopRowHolder.getChildCount()
                    + " laptop files shown. Photos open to view, videos/audio open to play, other files open in a compatible app.");
        }
    }

    private void loadLaptopFilesIfConfigured() {
        if (laptopLoadButton != null && !getSavedHelperLink().trim().isEmpty()
                && laptopRowHolder != null && laptopRowHolder.getChildCount() == 0) {
            loadLaptopFiles();
        }
    }

    private void loadLaptopFiles() {
        if (libraryHelperLink == null || laptopStatus == null || laptopRowHolder == null) return;
        String link = libraryHelperLink.getText().toString().trim();
        if (link.isEmpty()) {
            laptopStatus.setText("Start the Windows helper, then paste its LAN link on Connect.");
            return;
        }
        String requestedPath = laptopFolderPath.getText().toString().trim();
        String query = laptopFilter == null ? "" : laptopFilter.getText().toString().trim();
        laptopStatus.setText(requestedPath.isEmpty()
                ? (query.isEmpty() ? "Loading recent files from your Windows user folders…" : "Searching your Windows user folders for “" + query + "”…")
                : "Loading “" + requestedPath + "”…");
        laptopRowHolder.removeAllViews();
        if (laptopLoadButton != null) laptopLoadButton.setEnabled(false);
        fetchLaptopFiles(link, requestedPath, laptopRowHolder, laptopStatus, query,
                () -> { if (laptopLoadButton != null) laptopLoadButton.setEnabled(true); });
    }

    /** Turns a loopback helper link into one the phone can actually reach. */
    private android.net.Uri resolveLoopbackHelper(android.net.Uri parsed, String token, TextView status) throws Exception {
        runOnUiThread(() -> status.setText("That is the laptop's own address. Looking for the laptop on this network…"));
        String found = discoverHelperHost(token);
        if (found == null) {
            throw new Exception("That is the laptop's own address (127.0.0.1), so the phone cannot reach it. "
                    + "Copy the line marked LAN: from the helper window instead, for example http://192.168.43.42:7070/?t=…");
        }
        return android.net.Uri.parse(found);
    }

    /**
     * Finds the Windows helper on this network when the pasted link points at the
     * laptop's own loopback. Scans the /24 around each local address for port 7070
     * and keeps the first host that answers with the same token.
     */
    private String discoverHelperHost(String token) {
        java.util.List<String> prefixes = new java.util.ArrayList<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    java.net.InetAddress ip = address.getAddress();
                    if (ip == null || ip.getAddress().length != 4) continue;
                    String text = ip.getHostAddress();
                    prefixes.add(text.substring(0, text.lastIndexOf('.') + 1));
                }
            }
        } catch (Exception ignored) { }
        if (prefixes.isEmpty()) return null;

        java.util.concurrent.atomic.AtomicReference<String> found = new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(24);
        java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
        for (String prefix : prefixes) {
            for (int host = 1; host <= 254 && found.get() == null; host++) {
                final String encodedToken;
                try { encodedToken = java.net.URLEncoder.encode(token, "UTF-8"); }
                catch (Exception ignored) { continue; }
                final String candidate = "http://" + prefix + host + ":" + 7070 + "/?t=" + encodedToken;
                tasks.add(pool.submit(() -> {
                    if (found.get() != null) return;
                    java.net.HttpURLConnection probe = null;
                    try {
                        probe = (java.net.HttpURLConnection) new java.net.URL(candidate.replace("/?t=", "/files?t=")).openConnection();
                        probe.setConnectTimeout(250);
                        probe.setReadTimeout(400);
                        probe.setRequestMethod("GET");
                        if (probe.getResponseCode() == 200) found.compareAndSet(null, candidate);
                    } catch (Exception unreachable) {
                        // Not this host; keep scanning.
                    } finally {
                        if (probe != null) probe.disconnect();
                    }
                }));
            }
        }
        pool.shutdown();
        try { pool.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (java.util.concurrent.Future<?> task : tasks) task.cancel(true);
        return found.get();
    }

    /** Reads the helper's file list and fills the Library tab with it. */
    private void fetchLaptopFiles(String link, String requestedPath, LinearLayout rows, TextView status, String initialQuery, Runnable done) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                android.net.Uri parsed = android.net.Uri.parse(link.trim());
                String token = parsed.getQueryParameter("t");
                if (token == null || !"http".equalsIgnoreCase(parsed.getScheme())) {
                    throw new Exception("Use the full http link printed by the Windows helper, including the ?t= code.");
                }
                String host = parsed.getHost() == null ? "" : parsed.getHost();
                boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
                // The helper prints two links: "mo:" is the laptop talking to itself,
                // "LAN:" is the one a phone can reach. Rather than only refusing the
                // loopback one, look for the laptop on this network and use it.
                final android.net.Uri supplied = loopback ? resolveLoopbackHelper(parsed, token, status) : parsed;
                {
                    HttpURLConnection settingsConnection = null;
                    try {
                        android.net.Uri settingsUri = supplied.buildUpon().path("/settings").clearQuery()
                                .appendQueryParameter("t", token).build();
                        settingsConnection = (HttpURLConnection) new URL(settingsUri.toString()).openConnection();
                        settingsConnection.setRequestMethod("POST");
                        settingsConnection.setConnectTimeout(5000);
                        settingsConnection.setReadTimeout(10000);
                        settingsConnection.setDoOutput(true);
                        settingsConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        String selectedRoot = requestedPath.isEmpty() ? "@home" : requestedPath;
                        byte[] payload = new JSONObject().put("share_dir", selectedRoot).toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        try (java.io.OutputStream output = settingsConnection.getOutputStream()) { output.write(payload); }
                        int settingsCode = settingsConnection.getResponseCode();
                        if (settingsCode != 200) {
                            java.io.InputStream errorStream = settingsConnection.getErrorStream();
                            String errorBody = "";
                            if (errorStream != null) {
                                try (java.io.InputStream input = errorStream; java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                                    byte[] errorBuffer = new byte[2048];
                                    int errorRead;
                                    while ((errorRead = input.read(errorBuffer)) != -1 && bytes.size() < 8192) {
                                        bytes.write(errorBuffer, 0, Math.min(errorRead, 8192 - bytes.size()));
                                    }
                                    errorBody = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
                                }
                            }
                            throw new Exception(errorBody.isEmpty() ? "The laptop rejected that folder or file path (HTTP " + settingsCode + ")." : errorBody);
                        }
                    } finally {
                        if (settingsConnection != null) settingsConnection.disconnect();
                    }
                    runOnUiThread(() -> status.setText(requestedPath.isEmpty()
                            ? "Searching Windows user folders…" : "Location selected. Loading its files…"));
                }
                android.net.Uri.Builder filesBuilder = supplied.buildUpon().path("/files").clearQuery().appendQueryParameter("t", token);
                if (initialQuery != null && !initialQuery.trim().isEmpty()) filesBuilder.appendQueryParameter("q", initialQuery.trim());
                android.net.Uri filesUri = filesBuilder.build();
                connection = (HttpURLConnection) new URL(filesUri.toString()).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != 200) throw new Exception("Laptop helper returned HTTP " + connection.getResponseCode());
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                try (java.io.InputStream stream = connection.getInputStream()) {
                    while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                }
                JSONObject data = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                JSONArray items = data.optJSONArray("files");
                boolean truncated = data.optBoolean("truncated", false);
                runOnUiThread(() -> {
                    if (items == null || items.length() == 0) {
                        status.setText(initialQuery == null || initialQuery.trim().isEmpty()
                                ? "No files were found in the selected laptop folder. Check that the folder path exists."
                                : "No laptop files matched “" + initialQuery.trim() + "”. Try a shorter word or enter a folder path.");
                    } else {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;
                            String name = item.optString("name", "File");
                            String relative = item.optString("url", "");
                            String mime = item.optString("mime", "");
                            if (mime.isEmpty()) {
                                String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name);
                                mime = ext.isEmpty() ? "application/octet-stream"
                                        : valueOr(android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT)), "application/octet-stream");
                            }
                            String relativePath = item.optString("path", name);
                            android.net.Uri fileUri = supplied.buildUpon().encodedPath(relative).clearQuery().appendQueryParameter("t", token).build();
                            String kindLabel = mime.startsWith("video/") ? "VIDEO · CHOOSE PLAYER"
                                    : mime.startsWith("audio/") ? "AUDIO · TAP TO PLAY"
                                    : mime.startsWith("image/") ? "PHOTO · TAP TO VIEW"
                                    : "FILE · TAP TO OPEN";
                            Button row = button(kindLabel + "\n" + name + "\n" + relativePath + " · "
                                    + android.text.format.Formatter.formatShortFileSize(this, item.optLong("size")), COLOR_SURFACE);
                            row.setTextColor(COLOR_INK);
                            row.setTextSize(13);
                            row.setAllCaps(false);
                            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                            row.setTag(new String[]{name, mime, relativePath});
                            final String openMime = mime;
                            row.setOnClickListener(open -> {
                                if (openMime.startsWith("video/")) {
                                    openVideoWithChoice(fileUri, openMime, 0);
                                    return;
                                }
                                Intent view = new Intent(Intent.ACTION_VIEW);
                                view.setDataAndType(fileUri, openMime);
                                try { startActivity(view); }
                                catch (Exception error) { Toast.makeText(this, "No installed app can open this file type.", Toast.LENGTH_SHORT).show(); }
                            });
                            LinearLayout.LayoutParams lp = matchWrap();
                            lp.topMargin = dp(4);
                            rows.addView(row, lp);
                        }
                        status.setText(items.length() + " laptop file" + (items.length() == 1 ? "" : "s")
                                + (initialQuery == null || initialQuery.trim().isEmpty() ? " loaded." : " matched “" + initialQuery.trim() + "”.")
                                + (truncated ? " First 2,000 matches shown; narrow the search." : ""));
                        filterLaptopRows();
                    }
                    done.run();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setText("Could not read the laptop folder: " + error.getMessage());
                    done.run();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "PocketBridge-library-laptop").start();
    }

    private void postLaptopPowerAction(String link, String action, boolean confirmed, TextView status) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                android.net.Uri parsed = android.net.Uri.parse(link.trim());
                String token = parsed.getQueryParameter("t");
                if (token == null || !"http".equalsIgnoreCase(parsed.getScheme()))
                    throw new Exception("Paste the running Windows helper link in the Screen tab first.");
                String host = parsed.getHost() == null ? "" : parsed.getHost();
                boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
                android.net.Uri supplied = loopback ? resolveLoopbackHelper(parsed, token, status) : parsed;
                android.net.Uri powerUri = supplied.buildUpon().path("/power").clearQuery()
                        .appendQueryParameter("t", token).build();
                connection = (HttpURLConnection) new URL(powerUri.toString()).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = new JSONObject().put("action", action).put("confirm", confirmed).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(payload); }
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) {
                    java.io.InputStream errorStream = connection.getErrorStream();
                    String detail = "";
                    if (errorStream != null) {
                        try (java.io.InputStream input = errorStream; java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                            byte[] buffer = new byte[1024]; int read;
                            while ((read = input.read(buffer)) != -1 && bytes.size() < 4096) bytes.write(buffer, 0, read);
                            detail = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()).trim();
                        }
                    }
                    throw new Exception(detail.isEmpty() ? "Windows helper returned HTTP " + response + "." : detail);
                }
                String message;
                switch (action) {
                    case "lock": message = "Windows locked the laptop."; break;
                    case "wake-display": message = "Wake-display command sent. The laptop must be awake and the helper running."; break;
                    case "unlock": message = "Windows sign-in screen requested. Enter the PIN or password on the laptop; PocketBridge cannot bypass sign-in."; break;
                    case "sleep": message = "Windows accepted the sleep request. The helper will disconnect as the laptop sleeps."; break;
                    case "shutdown": message = "Windows scheduled shutdown in 15 seconds."; break;
                    default: message = "Windows command sent: " + action + ".";
                }
                final String confirmedMessage = message;
                runOnUiThread(() -> status.setText(confirmedMessage));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Laptop control failed: " + error.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "PocketBridge-laptop-power").start();
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
        ScrollView listScroll = new ScrollView(this);
        listScroll.addView(chatList);
        listScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(280)));
        addCard(parent, "SHARE CHAT", listScroll);

        chatInput = new EditText(this);
        chatInput.setHint("Write a message for the laptop");
        chatInput.setTextSize(14);
        chatInput.setMaxLines(3);
        addCard(parent, "YOUR MESSAGE", chatInput);

        Button send = button("Send message", COLOR_GREEN);
        send.setOnClickListener(v -> {
            String text = chatInput.getText().toString().trim();
            if (text.isEmpty()) return;
            ChatStore.append(this, text, "phone");
            chatInput.setText("");
            refreshChat();
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
        clear.setOnClickListener(v -> { ChatStore.clear(this); refreshChat(); });
        LinearLayout.LayoutParams clearGap = matchWrap();
        clearGap.topMargin = dp(8);
        parent.addView(clear, clearGap);
    }

    private void refreshChat() {
        if (chatList == null) return;
        org.json.JSONArray messages = ChatStore.read(this);
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
                    .setMessage("From the laptop:\n" + names + "\n\nOpen the latest file now, or choose any item in the chat.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Open latest", (dialog, which) -> openChatAttachment(attachment))
                    .show();
        }
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
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (Exception ignored) { Toast.makeText(this, "Could not open the shared file.", Toast.LENGTH_SHORT).show(); }
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
            boolean granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (granted) scanQrFromCamera();
            else Toast.makeText(this, "Allow camera access to scan QR transfers.", Toast.LENGTH_LONG).show();
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
        if (requestCode == REQUEST_CLONE_PROFILE) {
            cloneStatus.setText(resultCode == RESULT_OK
                    ? "Android accepted Work profile setup. When it finishes, open PocketBridge from the Work profile."
                    : "Work profile setup was cancelled or blocked by Android. No apps were copied.");
            cloneStatus.postDelayed(this::refreshCloneTab, 1200);
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
                screenButton.postDelayed(this::refreshScreenButton, 1500);
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
