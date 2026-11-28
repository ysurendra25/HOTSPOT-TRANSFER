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
    private static final int REQUEST_LIBRARY_FILES = 49;
    private MediaRepository pickerLibrary;
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
            boolean waitingForHotspotLink = ShareSessionService.MODE_HOTSPOT.equals(mode) && code != null && !code.isEmpty();
            String waitingLink = waitingForHotspotLink
                    ? "On the connected laptop, run ipconfig. In Edge enter http:// followed by the Wi-Fi IPv4 Default Gateway and :43117."
                    : "The link appears here when sharing starts.";
            linkText.setText(url == null || url.isEmpty() ? waitingLink : url);
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
            updateBandHint(active && ShareSessionService.MODE_HOTSPOT.equals(mode));
        }
    };

    private LinearLayout bandHintBox;
    private TextView bandHintText;

    /** Shows the 5 GHz hint in Connect while sharing over the phone hotspot. */
    private void updateBandHint(boolean hotspotMode) {
        if (bandHintBox == null) return;
        int band = HotspotBand.localHotspotBand;
        if (hotspotMode && band == HotspotBand.UNKNOWN) band = HotspotBand.detectTethered(this);
        HotspotBand.Hint hint = HotspotBand.hint(band, hotspotMode, HotspotBand.supports5Ghz(this));
        View card = (View) bandHintBox.getParent();
        if (card != null) card.setVisibility(hint == HotspotBand.Hint.NONE ? View.GONE : View.VISIBLE);
        bandHintText.setText(HotspotBand.message(hint));
    }

    private void openHotspotSettings() {
        for (Intent intent : HotspotBand.settingsIntents()) {
            try {
                startActivity(intent);
                return;
            } catch (Exception unavailable) { /* try the next settings screen */ }
        }
        Toast.makeText(this, "Open Settings, then Hotspot, and set the band to 5 GHz.", Toast.LENGTH_LONG).show();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        handleCameraIntent(getIntent());
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
        CameraCallNotifier.appVisible = true;
        chatHandler.removeCallbacks(edgePoll);
        edgePoll.run();
        if (tabViews != null && tabViews[3].getVisibility() == View.VISIBLE) {
            chatHandler.removeCallbacks(chatPoll);
            chatPoll.run();
        }
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
        CameraCall.INSTANCE.removeListener(cameraCallListener);
        PeerCameraLink.INSTANCE.removeListener(peerLinkListener);
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

    @Override protected void onPause() {
        CameraCallNotifier.appVisible = false;
        // A request that arrived while the app was open now needs its notification.
        CameraCallNotifier.refresh(this);
        super.onPause();
    }

    @Override protected void onStop() {
        chatHandler.removeCallbacks(chatPoll);
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        chatHandler.removeCallbacks(edgePoll);
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

        // 5 GHz hint: apps cannot choose the hotspot band, so point the user to it.
        bandHintBox = new LinearLayout(this);
        bandHintBox.setOrientation(LinearLayout.VERTICAL);
        bandHintText = new TextView(this);
        bandHintText.setTextColor(COLOR_INK);
        bandHintText.setTextSize(13);
        bandHintText.setLineSpacing(dp(3), 1f);
        bandHintBox.addView(bandHintText);
        Button bandSettings = button("Open hotspot settings", COLOR_SOFT);
        bandSettings.setTextColor(COLOR_INK);
        bandSettings.setTextSize(13);
        bandSettings.setMinHeight(dp(44));
        bandSettings.setOnClickListener(v -> openHotspotSettings());
        LinearLayout.LayoutParams bandButtonLp = matchWrap();
        bandButtonLp.topMargin = dp(8);
        bandHintBox.addView(bandSettings, bandButtonLp);
        addCard(content, "HOTSPOT SPEED", bandHintBox);
        updateBandHint(false);

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
        sendFromPhone.setOnClickListener(v -> choosePhoneFiles(REQUEST_SEND_FILE));
        content.addView(sendFromPhone, matchWrap());

        Button shareInFiles = button("Add files to Edge for this session", COLOR_GREEN);
        shareInFiles.setOnClickListener(v -> {
            pickerLibrary = MediaHttpServer.activeLibrary();
            if (pickerLibrary == null) {
                Toast.makeText(this, "Start Wi-Fi or hotspot sharing first.", Toast.LENGTH_SHORT).show();
                return;
            }
            choosePhoneFiles(REQUEST_LIBRARY_FILES);
        });
        content.addView(shareInFiles, matchWrap());

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
                {"Wake & show sign-in (PIN needed)", "unlock"}, {"Put laptop to sleep", "sleep"},
                {"Restart laptop", "restart"}, {"Shut down laptop", "shutdown"}};
        for (String[] powerAction : powerActions) {
            boolean disruptive = "shutdown".equals(powerAction[1]) || "sleep".equals(powerAction[1]) || "restart".equals(powerAction[1]);
            Button actionButton = button(powerAction[0], disruptive ? COLOR_SOFT : COLOR_GREEN);
            if (disruptive) actionButton.setTextColor(COLOR_INK);
            actionButton.setOnClickListener(v -> {
                String action = powerAction[1];
                if (disruptive) {
                    String dialogTitle = "shutdown".equals(action) ? "Shut down laptop" : "restart".equals(action) ? "Restart laptop" : "Put laptop to sleep";
                    String dialogMessage = "shutdown".equals(action) ? "Shut down Windows now? Unsaved work on the laptop may be lost and the connection will end."
                            : "restart".equals(action) ? "Restart Windows now? Unsaved work on the laptop may be lost. The connection returns after Windows and the helper start again."
                            : "Put Windows to sleep now? The laptop will disconnect until it wakes.";
                    String positive = "shutdown".equals(action) ? "Shut down" : "restart".equals(action) ? "Restart" : "Sleep";
                    new android.app.AlertDialog.Builder(this).setTitle(dialogTitle).setMessage(dialogMessage)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton(positive, (dialog, which) -> postLaptopPowerAction(
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

        // The PHONE's airplane-mode reset (refresh mobile data). Labelled as the
        // phone on purpose: the laptop's own radios are switched only on the laptop.
        LinearLayout airplaneCard = new LinearLayout(this);
        airplaneCard.setOrientation(LinearLayout.VERTICAL);
        TextView airplaneState = new TextView(this);
        airplaneState.setTextColor(COLOR_MUTED);
        airplaneState.setTextSize(12);
        airplaneState.setPadding(dp(4), 0, dp(4), dp(8));
        Runnable showAirplaneState = () -> {
            String method = AirplaneController.method(this);
            airplaneState.setText("This phone: airplane mode " + (AirplaneController.isAirplaneOn(this) ? "ON" : "OFF")
                    + " \u00b7 hotspot " + (AirplaneController.isHotspotOn(this) ? "ON" : "OFF")
                    + " \u00b7 " + ("secure-settings".equals(method) ? "automatic (adb grant)"
                    : "accessibility".equals(method) ? "automatic (accessibility)" : "guided steps"));
        };
        showAirplaneState.run();
        Button airplaneCycle = button("Refresh phone's mobile data (airplane ON \u2192 OFF \u2192 hotspot ON)", COLOR_GREEN);
        airplaneCycle.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Refresh this phone's mobile data")
                .setMessage("Switches THIS PHONE's airplane mode on and back off, then turns its hotspot on again. The laptop disconnects for a moment and rejoins by itself.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start", (dialog, which) -> {
                    if ("guided".equals(AirplaneController.method(this))) {
                        startActivity(new Intent(this, AirplaneCycleActivity.class));
                    } else if (!AirplaneController.startCycle(this, "phone")) {
                        Toast.makeText(this, "A reset is already running.", Toast.LENGTH_SHORT).show();
                    } else {
                        airplaneState.setText("Resetting\u2026 the hotspot drops for a few seconds.");
                    }
                }).show());
        airplaneCard.addView(airplaneState, matchWrap());
        airplaneCard.addView(airplaneCycle, matchWrap());
        TextView airplaneHelp = new TextView(this);
        airplaneHelp.setText("Android does not let apps switch airplane mode or the hotspot. Without setup, PocketBridge walks you through it. "
                + "To make it automatic, enable \u201cPocketBridge airplane reset\u201d in Accessibility, or run once from a computer: "
                + AirplaneController.ADB_GRANT);
        airplaneHelp.setTextColor(COLOR_MUTED); airplaneHelp.setTextSize(11);
        airplaneHelp.setPadding(dp(4), dp(8), dp(4), 0);
        airplaneCard.addView(airplaneHelp, matchWrap());
        airplaneCard.setOnClickListener(v -> showAirplaneState.run());
        addCard(content, "PHONE AIRPLANE MODE", airplaneCard);

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
        chatHandler.removeCallbacks(edgePoll);
        if (index == 1 || index == 2) syncHelperLinkFields();
        if (index == 2) {
            edgePoll.run();
            if (helperLoad.isEnabled() && laptopFileAdapter.getTotalCount() == 0
                    && !libraryHelperLink.getText().toString().trim().isEmpty()) helperLoad.performClick();
        }
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

    // ---- Camera call: two-sided approval (CameraCall on this phone, PeerCameraLink to another phone) ----
    private boolean cameraPeerMode;
    private Button cameraHostModeButton, cameraPeerModeButton, cameraAccept, cameraDecline, cameraSwitch, cameraCopy, cameraPeerConnect;
    private LinearLayout cameraPromptBox, cameraPeerBox;
    private TextView cameraPromptText, cameraLiveBadge;
    private EditText cameraPeerLink;
    private String pendingCameraAction = "";
    private final CameraCall.Listener cameraCallListener = call -> runOnUiThread(this::refreshCameraStatus);
    private final PeerCameraLink.Listener peerLinkListener = link -> runOnUiThread(this::refreshCameraStatus);

    private void buildCameraTab(LinearLayout parent) {
        TextView intro = new TextView(this);
        intro.setText("A private, two-way camera call over your hotspot, with a laptop (Edge) or another phone. One side asks, the other accepts. No camera turns on until both sides have accepted and allowed camera access, and either side can end it for both.");
        intro.setTextColor(COLOR_MUTED);
        intro.setTextSize(14);
        intro.setLineSpacing(dp(3), 1f);
        addCard(parent, "CAMERA CALL · BOTH SIDES APPROVE", intro);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        cameraHostModeButton = button("Laptop or phone joins me", COLOR_GREEN);
        cameraPeerModeButton = button("Call another phone", COLOR_SOFT);
        cameraHostModeButton.setTextSize(12);
        cameraPeerModeButton.setTextSize(12);
        cameraHostModeButton.setOnClickListener(v -> setCameraPeerMode(false));
        cameraPeerModeButton.setOnClickListener(v -> setCameraPeerMode(true));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f);
        half2.leftMargin = dp(4);
        modes.addView(cameraHostModeButton, half);
        modes.addView(cameraPeerModeButton, half2);
        addCard(parent, "CALL WITH", modes);

        cameraPeerBox = new LinearLayout(this);
        cameraPeerBox.setOrientation(LinearLayout.VERTICAL);
        cameraPeerLink = new EditText(this);
        cameraPeerLink.setSingleLine(false);
        cameraPeerLink.setTextSize(14);
        cameraPeerLink.setHint("Other phone's link and code · http://192.168.43.1:43117 Code: 123456");
        cameraPeerLink.setText(getPreferences(MODE_PRIVATE).getString("peer_camera_link", ""));
        cameraPeerBox.addView(cameraPeerLink, matchWrap());
        cameraPeerConnect = button("Connect to that phone", COLOR_SOFT);
        cameraPeerConnect.setTextColor(COLOR_INK);
        cameraPeerConnect.setOnClickListener(v -> togglePeerLink());
        cameraPeerBox.addView(cameraPeerConnect, matchWrap());
        addCard(parent, "OTHER PHONE (ITS COPY LINK AND CODE)", cameraPeerBox);

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        cameraLiveBadge = new TextView(this);
        cameraLiveBadge.setText("●  LIVE · camera on");
        cameraLiveBadge.setTextColor(Color.WHITE);
        cameraLiveBadge.setBackgroundColor(Color.rgb(0xd3, 0x2f, 0x2f));
        cameraLiveBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cameraLiveBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        cameraLiveBadge.setVisibility(View.GONE);
        statusBox.addView(cameraLiveBadge, new LinearLayout.LayoutParams(-2, -2));
        cameraStatus = new TextView(this);
        cameraStatus.setText("Camera is off.");
        cameraStatus.setTextColor(COLOR_INK);
        cameraStatus.setTextSize(14);
        cameraStatus.setLineSpacing(dp(3), 1f);
        statusBox.addView(cameraStatus, matchWrap());
        addCard(parent, "CALL STATUS", statusBox);

        cameraPromptBox = new LinearLayout(this);
        cameraPromptBox.setOrientation(LinearLayout.VERTICAL);
        cameraPromptText = new TextView(this);
        cameraPromptText.setTextColor(COLOR_INK);
        cameraPromptText.setTextSize(15);
        cameraPromptBox.addView(cameraPromptText, matchWrap());
        LinearLayout answers = new LinearLayout(this);
        answers.setOrientation(LinearLayout.HORIZONTAL);
        cameraAccept = button("Accept", COLOR_GREEN);
        cameraDecline = button("Decline", COLOR_SOFT);
        cameraDecline.setTextColor(COLOR_INK);
        cameraAccept.setOnClickListener(v -> runCameraAction("accept"));
        cameraDecline.setOnClickListener(v -> declineCameraCall());
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(0, -2, 1f);
        d.leftMargin = dp(4);
        answers.addView(cameraAccept, a);
        answers.addView(cameraDecline, d);
        cameraPromptBox.addView(answers, matchWrap());
        addCard(parent, "INCOMING CAMERA CALL", cameraPromptBox);
        ((View) cameraPromptBox.getParent()).setVisibility(View.GONE);

        cameraToggle = button("Start camera session", COLOR_GREEN);
        cameraToggle.setOnClickListener(v -> toggleCameraSession());
        parent.addView(cameraToggle, matchWrap());

        cameraSwitch = button("Switch front / back camera", COLOR_SOFT);
        cameraSwitch.setTextColor(COLOR_INK);
        cameraSwitch.setOnClickListener(v -> CameraSessionService.switchCamera());
        cameraSwitch.setVisibility(View.GONE);
        parent.addView(cameraSwitch, matchWrap());

        cameraCopy = button("Copy phone link and access code", COLOR_SOFT);
        cameraCopy.setTextColor(COLOR_INK);
        cameraCopy.setOnClickListener(v -> {
            String link = linkText == null ? "" : linkText.getText().toString().trim();
            String accessCode = codeText == null ? "" : codeText.getText().toString().replaceAll("\\s", "");
            if (!link.startsWith("http://") || accessCode.length() != 6) {
                Toast.makeText(this, "Start Wi-Fi or hotspot sharing first.", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketBridge camera", link + "\nCode: " + accessCode));
                Toast.makeText(this, "Copied. Paste it in the other phone's Camera tab, or open the link in Edge.", Toast.LENGTH_LONG).show();
            }
        });
        parent.addView(cameraCopy, matchWrap());

        phoneCameraPreview = new org.webrtc.SurfaceViewRenderer(this);
        // SurfaceView draws camera pixels on a separate surface behind the view.
        // An opaque background on the renderer itself covers those pixels.
        phoneCameraPreview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240)));
        phoneCameraPreview.setEnableHardwareScaler(true);
        phoneCameraPreview.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        phoneCameraPreview.setVisibility(View.GONE);
        addCard(parent, "THIS PHONE'S CAMERA · SENT", phoneCameraPreview);

        laptopCameraPreview = new org.webrtc.SurfaceViewRenderer(this);
        laptopCameraPreview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240)));
        laptopCameraPreview.setEnableHardwareScaler(true);
        laptopCameraPreview.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        laptopCameraPreview.setVisibility(View.GONE);
        addCard(parent, "OTHER CAMERA · RECEIVED HERE", laptopCameraPreview);
        CameraSessionService.attachRenderers(phoneCameraPreview, laptopCameraPreview);

        Button fullscreen = button("Full screen laptop camera", COLOR_SOFT);
        fullscreen.setTextColor(COLOR_INK);
        fullscreen.setOnClickListener(v -> showCameraFullscreen());
        parent.addView(fullscreen, matchWrap());

        TextView help = new TextView(this);
        help.setText("Laptop: run the Windows helper from this APK, open this phone's link in Edge, choose Camera and either Request a call or Accept the phone's request. Another phone: on that phone tap Copy phone link and access code, then here choose Call another phone, paste it and Connect.");
        help.setTextColor(COLOR_MUTED);
        help.setTextSize(12);
        help.setLineSpacing(dp(3), 1f);
        help.setPadding(dp(4), dp(12), dp(4), 0);
        parent.addView(help, matchWrap());
        CameraCall.INSTANCE.addListener(cameraCallListener);
        PeerCameraLink.INSTANCE.addListener(peerLinkListener);
        CameraCallNotifier.install(this);
        setCameraPeerMode(PeerCameraLink.INSTANCE.connected() || CameraSessionService.MODE_PEER.equals(CameraSessionService.mode()));
    }

    private void setCameraPeerMode(boolean peer) {
        if (CameraSessionService.isActive() && peer != CameraSessionService.MODE_PEER.equals(CameraSessionService.mode())) {
            Toast.makeText(this, "End the current camera call first.", Toast.LENGTH_SHORT).show();
            peer = cameraPeerMode;
        }
        cameraPeerMode = peer;
        if (cameraHostModeButton != null) {
            cameraHostModeButton.setBackgroundColor(peer ? COLOR_SOFT : COLOR_GREEN);
            cameraHostModeButton.setTextColor(peer ? COLOR_INK : Color.WHITE);
            cameraPeerModeButton.setBackgroundColor(peer ? COLOR_GREEN : COLOR_SOFT);
            cameraPeerModeButton.setTextColor(peer ? Color.WHITE : COLOR_INK);
            ((View) cameraPeerBox.getParent()).setVisibility(peer ? View.VISIBLE : View.GONE);
            cameraCopy.setVisibility(peer ? View.GONE : View.VISIBLE);
        }
        refreshCameraStatus();
    }

    private void togglePeerLink() {
        PeerCameraLink link = PeerCameraLink.INSTANCE;
        if (link.connected()) {
            if (CameraSessionService.isActive()) stopService(new Intent(this, CameraSessionService.class));
            link.disconnect();
            refreshCameraStatus();
            return;
        }
        String text = cameraPeerLink.getText().toString();
        PeerCameraLink.Target target = PeerCameraLink.parse(text);
        if (target == null) {
            cameraStatus.setText("Paste the other phone's link and six-digit code (its Camera tab → Copy phone link and access code).");
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("peer_camera_link", text).apply();
        link.connect(target);
        cameraStatus.setText("Connecting to " + target.origin + "…");
    }

    /** What the Camera tab shows: the call on this phone (host) or on the other phone (peer). */
    private static final class CameraView {
        String state = "idle";
        boolean weAsked;
        boolean awaitingUs;
        long expiresInMs;
        String reason = "";
        String who = "";
    }

    private CameraView cameraView() {
        CameraView view = new CameraView();
        if (cameraPeerMode) {
            PeerCameraLink link = PeerCameraLink.INSTANCE;
            JSONObject status = link.status();
            view.state = status.optString("state", "idle");
            view.weAsked = CameraCall.REMOTE.equals(status.optString("requester"));
            view.awaitingUs = link.awaitingUs();
            view.expiresInMs = status.optLong("expiresInMs", 0);
            view.reason = link.error().isEmpty() ? status.optString("reason", "") : link.error();
            view.who = "The other phone";
        } else {
            CameraCall call = CameraCall.INSTANCE;
            call.tick();
            view.state = call.state().name().toLowerCase(java.util.Locale.ROOT);
            view.weAsked = CameraCall.PHONE.equals(call.requester());
            view.awaitingUs = call.awaiting(CameraCall.PHONE);
            view.expiresInMs = call.expiresInMs();
            view.reason = call.reason();
            view.who = call.remoteName().isEmpty() ? "A laptop or phone on your hotspot" : call.remoteName();
        }
        return view;
    }

    private void toggleCameraSession() {
        CameraView view = cameraView();
        boolean ongoing = "requested".equals(view.state) || "accepted".equals(view.state) || "live".equals(view.state);
        if (ongoing && !view.awaitingUs) { endCameraCall(); return; }
        if (CameraSessionService.isActive()) { endCameraCall(); return; }
        runCameraAction("request");
    }

    /** Request or accept: both need Android's camera permission first. */
    private void runCameraAction(String action) {
        if (cameraPeerMode && !PeerCameraLink.INSTANCE.connected()) {
            cameraStatus.setText("Paste the other phone's link and code, then tap Connect to that phone.");
            return;
        }
        if (!cameraPeerMode && !sharing && "request".equals(action)) {
            cameraStatus.setText("Start a Wi-Fi or hotspot share first so the laptop or other phone can reach this phone.");
            return;
        }
        if (CameraSessionService.isActive() && cameraPeerMode != CameraSessionService.MODE_PEER.equals(CameraSessionService.mode())) {
            cameraStatus.setText("This phone's camera is in another call. End it first.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraAction = action;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        boolean peer = cameraPeerMode;
        if (!peer) {
            try {
                if ("accept".equals(action)) CameraCall.INSTANCE.respond(CameraCall.PHONE, CameraCall.INSTANCE.id(), true);
                else CameraCall.INSTANCE.request(CameraCall.PHONE, "");
                startCameraSession(CameraSessionService.MODE_HOST);
            } catch (CameraCall.Refused refused) {
                cameraStatus.setText(refused.getMessage());
            }
            refreshCameraStatus();
            return;
        }
        cameraStatus.setText("accept".equals(action) ? "Accepting…" : "Asking the other phone…");
        background.execute(() -> {
            try {
                if ("accept".equals(action)) PeerCameraLink.INSTANCE.respond(true);
                else PeerCameraLink.INSTANCE.request();
                runOnUiThread(() -> { startCameraSession(CameraSessionService.MODE_PEER); refreshCameraStatus(); });
            } catch (Exception error) {
                runOnUiThread(() -> cameraStatus.setText(valueOr(error.getMessage(), "The other phone did not answer.")));
            }
        });
    }

    private void declineCameraCall() {
        if (cameraPeerMode) {
            background.execute(() -> {
                try { PeerCameraLink.INSTANCE.respond(false); } catch (Exception ignored) { }
                runOnUiThread(this::refreshCameraStatus);
            });
        } else {
            try { CameraCall.INSTANCE.respond(CameraCall.PHONE, CameraCall.INSTANCE.id(), false); }
            catch (CameraCall.Refused ignored) { }
            refreshCameraStatus();
        }
    }

    /** Ends the call for both sides. */
    private void endCameraCall() {
        if (cameraPeerMode) background.execute(PeerCameraLink.INSTANCE::stopCall);
        else CameraCall.INSTANCE.stop(CameraCall.PHONE, null);
        stopService(new Intent(this, CameraSessionService.class));
        refreshCameraStatus();
    }

    /** From the incoming-call notification's Accept. */
    private void handleCameraIntent(Intent intent) {
        if (intent == null || !CameraCallNotifier.ACTION_ACCEPT.equals(intent.getAction())) return;
        intent.setAction(null);
        if (tabViews != null) showTab(4);
        setCameraPeerMode("peer".equals(intent.getStringExtra(CameraCallNotifier.EXTRA_CHANNEL)));
        String id = intent.getStringExtra(CameraCallNotifier.EXTRA_ID);
        String current = cameraPeerMode ? PeerCameraLink.INSTANCE.callId() : CameraCall.INSTANCE.id();
        if (id != null && id.equals(current) && cameraView().awaitingUs) runCameraAction("accept");
        else refreshCameraStatus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCameraIntent(intent);
    }

    private void startCameraSession(String mode) {
        Intent service = new Intent(this, CameraSessionService.class).putExtra(CameraSessionService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        cameraHandler.removeCallbacks(cameraStatusPoll);
        cameraHandler.postDelayed(cameraStatusPoll, 500);
        CameraSessionService.attachRenderers(phoneCameraPreview, laptopCameraPreview);
    }

    private void refreshCameraStatus() {
        if (cameraStatus == null) return;
        CameraView view = cameraView();
        boolean requested = "requested".equals(view.state);
        boolean ongoing = requested || "accepted".equals(view.state) || "live".equals(view.state);
        boolean cameraOpen = CameraSessionService.isRunning();
        boolean live = CameraSessionService.isLive();
        View promptCard = (View) cameraPromptBox.getParent();
        promptCard.setVisibility(view.awaitingUs ? View.VISIBLE : View.GONE);
        if (view.awaitingUs) {
            cameraPromptText.setText(view.who + " wants a two-way camera call. Nothing is shared until you accept. "
                    + Math.max(0, (view.expiresInMs + 999) / 1000) + " s left.");
        }
        cameraLiveBadge.setVisibility(live ? View.VISIBLE : View.GONE);
        cameraSwitch.setVisibility(cameraOpen ? View.VISIBLE : View.GONE);
        cameraToggle.setVisibility(view.awaitingUs ? View.GONE : View.VISIBLE);
        cameraToggle.setText(ongoing || CameraSessionService.isActive()
                ? (requested ? "Cancel camera request" : "End camera call for both")
                : "Start camera session");
        if (cameraPeerConnect != null) cameraPeerConnect.setText(PeerCameraLink.INSTANCE.connected() ? "Disconnect from that phone" : "Connect to that phone");
        String text;
        if (CameraSessionService.isActive() && (cameraOpen || CameraSessionService.isStarting() || requested || ongoing)) {
            text = CameraSessionService.statusText();
        } else if (requested && view.weAsked) {
            text = "Waiting for the other side to accept (" + Math.max(0, (view.expiresInMs + 999) / 1000) + " s). The camera stays off until both accept.";
        } else if (requested) {
            text = view.who + " is asking for a camera call.";
        } else if (cameraPeerMode && !PeerCameraLink.INSTANCE.connected()) {
            text = "Paste the other phone's link and code, then Connect. Either phone can then start the call.";
        } else if (!view.reason.isEmpty()) {
            text = view.reason;
        } else {
            text = cameraPeerMode ? "Connected. Tap Start camera session to ask the other phone, or wait for its request."
                    : "Camera is off. Tap Start camera session to ask, or accept a request from Edge or another phone.";
        }
        cameraStatus.setText(text);
        if (phoneCameraPreview != null) phoneCameraPreview.setVisibility(cameraOpen ? View.VISIBLE : View.GONE);
        if (laptopCameraPreview != null) laptopCameraPreview.setVisibility(cameraOpen ? View.VISIBLE : View.GONE);
        cameraHandler.removeCallbacks(cameraStatusPoll);
        if (ongoing || CameraSessionService.isActive() || (tabViews != null && tabViews[4].getVisibility() == View.VISIBLE)) {
            cameraHandler.postDelayed(cameraStatusPoll, 500);
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

        edgeStatus = new TextView(this);
        edgeStatus.setTextColor(COLOR_MUTED);
        edgeStatus.setText("Checking files shared from Edge…");
        addCard(laptopLibrarySection, "SHARED FROM EDGE", edgeStatus);
        edgeList = newLaptopList();
        edgeList.setVisibility(View.GONE);
        edgeAdapter = new LaptopFileAdapter();
        edgeList.setAdapter(edgeAdapter);
        laptopLibrarySection.addView(edgeList);

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
        String[] remoteKinds = LAPTOP_KINDS;
        String[] remoteLabels = {"All", "Videos", "Music", "Photos", "Docs", "Apps", "Other"};
        laptopKindButtons = new Button[remoteKinds.length];
        for (int i = 0; i < remoteKinds.length; i++) {
            final String kind = remoteKinds[i];
            Button chip = button(remoteLabels[i], "all".equals(kind) ? COLOR_GREEN : COLOR_SOFT);
            if (!"all".equals(kind)) chip.setTextColor(COLOR_INK);
            chip.setTextSize(11);
            laptopKindButtons[i] = chip;
            chip.setOnClickListener(v -> setLaptopFilterKind(kind));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = dp(3);
            chip.setMinWidth(dp(64));
            laptopKinds.addView(chip, lp);
        }
        android.widget.HorizontalScrollView kindScroll = new android.widget.HorizontalScrollView(this);
        kindScroll.setHorizontalScrollBarEnabled(false);
        kindScroll.addView(laptopKinds);
        addCard(laptopLibrarySection, "FILE TYPE", kindScroll);

        laptopSortButton = button("Sort: Newest first ▾", COLOR_SOFT);
        laptopSortButton.setTextColor(COLOR_INK);
        laptopSortButton.setTextSize(12);
        laptopSortButton.setOnClickListener(v -> chooseLaptopSort());
        addCard(laptopLibrarySection, "SORT", laptopSortButton);
        laptopSort = savedLibrarySort(laptopFilterKind);
        showLaptopSort();

        TextView laptopStatusView = new TextView(this);
        laptopStatusView.setText("The helper shares Downloads by default. Enter a Windows folder or exact file path to switch locations.");
        laptopStatusView.setTextColor(COLOR_MUTED);
        laptopStatusView.setTextSize(12);
        laptopStatusView.setPadding(dp(4), 0, dp(4), dp(10));
        laptopLibrarySection.addView(laptopStatusView);

        laptopFileList = newLaptopList();
        laptopFileAdapter = new LaptopFileAdapter();
        laptopFileList.setAdapter(laptopFileAdapter);
        addCard(laptopLibrarySection, "LAPTOP FILES", laptopFileList);

        Button loadLaptop = button("Load laptop files", COLOR_GREEN);
        helperLoad = loadLaptop;
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

    private ListView newLaptopList() {
        ListView list = new ListView(this) {
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
        list.setDivider(new android.graphics.drawable.ColorDrawable(COLOR_LINE));
        list.setDividerHeight(dp(1));
        list.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(380)));
        return list;
    }

    private LaptopFileAdapter edgeAdapter;
    private ListView edgeList;
    private TextView edgeStatus;
    private Button helperLoad;
    private EdgeFiles lastEdge;
    private long edgeRevision = -1, edgeChatRevision = -1;
    private boolean edgeReading;
    private final Runnable edgePoll = new Runnable() {
        @Override public void run() {
            if (tabViews == null || tabViews[2].getVisibility() != View.VISIBLE || !receiverRegistered) return;
            refreshEdgeFiles();
            chatHandler.postDelayed(this, 1000);
        }
    };

    private void refreshEdgeFiles() {
        EdgeFiles edge = MediaHttpServer.activeEdgeFiles;
        long revision = edge == null ? 0 : edge.revision();
        long chatRevision = ChatStore.version();
        if (edgeReading || (lastEdge == edge && edgeRevision == revision && edgeChatRevision == chatRevision)) return;
        edgeReading = true;
        background.execute(() -> {
            List<JSONObject> rows = edge == null ? new ArrayList<>() : edge.snapshot();
            rows.addAll(EdgeInbox.list(this));
            runOnUiThread(() -> {
                edgeReading = false;
                if (isDestroyed() || edge != MediaHttpServer.activeEdgeFiles) return;
                edgeRevision = revision;
                edgeChatRevision = chatRevision;
                lastEdge = edge;
                edgeAdapter.setItems(rows, null);
                edgeList.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
                edgeStatus.setText(rows.isEmpty() ? "In Edge, open Connect → Share folder. Its files will appear here automatically."
                        : rows.size() + " laptop files. Tap a file to open it, or use its save button.");
            });
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

    static final String[] LAPTOP_KINDS = {"all", "videos", "audio", "images", "documents", "apps", "other"};
    static final String[] SORT_LABELS = {"Name A–Z", "Name Z–A", "Newest first", "Oldest first", "Largest first", "Smallest first"};
    private Button laptopSortButton;
    private LibrarySort laptopSort = LibrarySort.DEFAULT;

    /** Sort choice per Library category, kept across app restarts. */
    private LibrarySort savedLibrarySort(String kind) {
        return LibrarySort.parse(getSharedPreferences("library", MODE_PRIVATE).getString("sort_" + kind, LibrarySort.DEFAULT.id));
    }

    private void saveLibrarySort(String kind, LibrarySort sort) {
        getSharedPreferences("library", MODE_PRIVATE).edit().putString("sort_" + kind, sort.id).apply();
    }

    private void showLaptopSort() {
        if (laptopSortButton != null) laptopSortButton.setText("Sort: " + SORT_LABELS[laptopSort.ordinal()] + " ▾");
    }

    private void chooseLaptopSort() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Sort " + laptopKindLabel(laptopFilterKind))
                .setSingleChoiceItems(SORT_LABELS, laptopSort.ordinal(), (dialog, which) -> {
                    laptopSort = LibrarySort.values()[which];
                    saveLibrarySort(laptopFilterKind, laptopSort);
                    showLaptopSort();
                    filterLaptopRows();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String laptopKindLabel(String kind) {
        switch (kind) {
            case "videos": return "videos";
            case "audio": return "music";
            case "images": return "photos";
            case "documents": return "documents";
            case "apps": return "apps";
            case "other": return "other files";
            default: return "all files";
        }
    }

    /** Laptop and Edge rows are JSON objects; these fields feed LibrarySort. */
    static final LibrarySort.Fields<JSONObject> JSON_FIELDS = new LibrarySort.Fields<JSONObject>() {
        @Override public String name(JSONObject row) { return row.optString("name", ""); }
        @Override public long modified(JSONObject row) { return row.optLong("modified", 0L); }
        @Override public long size(JSONObject row) { return row.optLong("size", 0L); }
        @Override public String key(JSONObject row) { return row.optString("path", row.optString("url", "")); }
    };

    /** Filters JSON file rows by Library category and search text, then sorts them. */
    static List<JSONObject> filterAndSortRows(List<JSONObject> rows, String queryText, String kind, LibrarySort sort) {
        String query = queryText == null ? "" : queryText.trim().toLowerCase(java.util.Locale.ROOT);
        String filter = MediaRepository.normalizeKind(kind);
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : rows) {
            String name = item.optString("name", "File");
            String mime = item.optString("mime", "");
            if (mime.isEmpty()) mime = MediaTypeClassifier.mimeType(name, null);
            String path = item.optString("path", name);
            boolean typeMatches = MediaRepository.matches(filter, "file", mime, name);
            if (typeMatches && (query.isEmpty() || (name + " " + path + " " + mime).toLowerCase(java.util.Locale.ROOT).contains(query))) out.add(item);
        }
        out.sort((sort == null ? LibrarySort.DEFAULT : sort).comparator(JSON_FIELDS));
        return out;
    }

    private void setLaptopFilterKind(String kind) {
        laptopFilterKind = kind;
        laptopSort = savedLibrarySort(kind);
        showLaptopSort();
        if (laptopKindButtons != null) {
            String[] values = LAPTOP_KINDS;
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
        if (edgeAdapter != null) edgeAdapter.filter(laptopFilter.getText().toString(), laptopFilterKind);
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
                String exactFile = "";
                if (!requestedPath.isEmpty()) {
                    byte[] payload = new JSONObject().put("share_dir", requestedPath).toString()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    HelperClient.Response chosen = HelperClient.request("POST", helper.url("/settings"), payload,
                            HelperClient.CONNECT_TIMEOUT_MS, HelperClient.READ_TIMEOUT_MS, 2);
                    if (!chosen.ok()) throw new HelperClient.HelperException(HelperClient.errorMessage(chosen.status, chosen.body), chosen.status);
                    exactFile = chosen.json().optString("share_file", "");
                }
                final boolean exact = !exactFile.isEmpty();
                String effectiveQuery = exactFile.isEmpty() ? initialQuery : "";
                runOnUiThread(() -> status.setText(requestedPath.isEmpty()
                        ? "Loading the helper's default shared folder…"
                        : !exact ? "Location selected. Loading its files…" : "Exact file selected. Loading it…"));
                int offset = 0;
                int shown = 0;
                boolean truncated = false;
                int total = 0;
                while (shown < LAPTOP_MAX_ROWS) {
                    HelperClient.Response response = HelperClient.request("GET",
                            helper.url("/files", "offset", String.valueOf(offset), "limit", String.valueOf(LAPTOP_PAGE_SIZE),
                                    "refresh", offset == 0 ? "1" : "0", "q", effectiveQuery, "sort", laptopSort.id),
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
                        String more = finalTotal > finalShown ? " Showing the first " + finalShown + " of " + finalTotal + "." : "";
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
            visible.clear();
            visible.addAll(filterAndSortRows(all, queryText, kind, laptopSort));
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
            boolean local = item.has("attachmentId");
            String url = currentHelper != null ? currentHelper.url(relative)
                    : "http://127.0.0.1:" + MediaHttpServer.PORT + relative;
            boolean ready = currentHelper != null || (relative.startsWith("/api/edge-media/") && MediaHttpServer.activeEdgeFiles != null);
            boolean downloading = laptopDownloadsInProgress.contains(url);
            boolean downloaded = laptopDownloadsComplete.contains(url);
            download.setText(local ? "Save to Downloads" : downloading ? "Downloading…" : downloaded ? "Saved to Downloads" : "Download to phone");
            download.setEnabled(local || (ready && !downloading));
            download.setOnClickListener(view -> {
                if (local) { saveChatAttachmentToDownloads(item); return; }
                if (ready && laptopDownloadsInProgress.add(url)) {
                    notifyDataSetChanged();
                    downloadLaptopFile(url, name, item.optLong("size", -1));
                }
            });
            row.setOnClickListener(view -> {
                if (local) { openChatAttachment(item); return; }
                if (ready) openLaptopFile(android.net.Uri.parse(url), openMime);
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

    private void downloadLaptopFile(String url, String name, long listedSize) {
        background.execute(() -> {
            java.io.File received = null;
            DownloadPublisher.Notifier notifier = new DownloadPublisher.Notifier(this, LaptopFileTransfer.safeName(name));
            notifier.stage("Downloading");
            try {
                received = LaptopFileTransfer.download(this, url,
                        new java.io.File(getFilesDir(), "received"), name, listedSize, notifier);
                long bytes = received.length();
                ChatStore.appendFile(this, LaptopFileTransfer.safeName(name), received.getName(), bytes, "laptop");
                // The chat keeps its own copy for the laptop page; the user's copy goes to
                // system Downloads so it shows in Files and the Downloads list.
                String mime = MediaTypeClassifier.mimeType(name, null);
                String savedWhere;
                try {
                    android.net.Uri saved = DownloadPublisher.publish(this, received, name, mime, notifier.stage("Saving"));
                    notifier.done(saved, mime);
                    savedWhere = "Saved to Downloads and added to Share chat.";
                } catch (Exception notPublished) {
                    notifier.failed(valueOr(notPublished.getMessage(), "Could not add it to Downloads."));
                    savedWhere = "Saved in Share chat, but not in Downloads: " + valueOr(notPublished.getMessage(), "storage error");
                }
                final String result = savedWhere;
                laptopDownloadsInProgress.remove(url);
                laptopDownloadsComplete.add(url);
                runOnUiThread(() -> {
                    if (laptopFileAdapter != null) laptopFileAdapter.notifyDataSetChanged();
                    if (edgeAdapter != null) edgeAdapter.notifyDataSetChanged();
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                    showTab(3);
                });
            } catch (Exception error) {
                if (received != null) { /*noinspection ResultOfMethodCallIgnored*/ received.delete(); }
                laptopDownloadsInProgress.remove(url);
                String message = error.getMessage() == null ? "Could not download this laptop file." : error.getMessage();
                notifier.failed(message);
                runOnUiThread(() -> {
                    if (laptopFileAdapter != null) laptopFileAdapter.notifyDataSetChanged();
                    if (edgeAdapter != null) edgeAdapter.notifyDataSetChanged();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void postLaptopPowerAction(String link, String action, boolean confirmed, TextView status) {
        status.setText("Sending to the laptop…");
        background.execute(() -> {
            try {
                LaptopPowerClient.Outcome outcome = LaptopPowerClient.run(LaptopPowerClient.lanLink(link), action, confirmed,
                        LaptopRelay.INSTANCE, message -> runOnUiThread(() -> status.setText(message)));
                String shown = (outcome.ok ? "\u2713 " : "Laptop control failed: ") + outcome.message;
                runOnUiThread(() -> {
                    status.setText(shown);
                    Toast.makeText(this, outcome.ok ? outcome.message : shown, Toast.LENGTH_LONG).show();
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
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
        attach.setOnClickListener(v -> choosePhoneFiles(REQUEST_SEND_FILE));
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
                // Buttons by type (see ChatActions): video Play / Play in VLC / Download,
                // music Play / Download, anything else Open / Download.
                LinearLayout actionRow = new LinearLayout(this);
                actionRow.setOrientation(LinearLayout.HORIZONTAL);
                List<ChatActions.Action> actions = ChatActions.forFile(mime, name);
                for (int a = 0; a < actions.size(); a++) {
                    ChatActions.Action chatAction = actions.get(a);
                    Button actionButton = button(ChatActions.label(chatAction),
                            chatAction == ChatActions.Action.DOWNLOAD ? COLOR_GREEN : COLOR_SOFT);
                    if (chatAction != ChatActions.Action.DOWNLOAD) actionButton.setTextColor(COLOR_INK);
                    actionButton.setTextSize(12);
                    actionButton.setMinHeight(dp(40));
                    actionButton.setPadding(dp(4), 0, dp(4), 0);
                    actionButton.setOnClickListener(v -> runChatAction(chatAction, attachment));
                    LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, -2, 1f);
                    if (a > 0) actionLp.leftMargin = dp(6);
                    actionRow.addView(actionButton, actionLp);
                }
                LinearLayout.LayoutParams actionRowLp = matchWrap();
                actionRowLp.topMargin = dp(6);
                fileCard.addView(actionRow, actionRowLp);
                LinearLayout.LayoutParams fileLp = matchWrap();
                fileLp.topMargin = dp(7);
                row.addView(fileCard, fileLp);
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
                    .setMessage("From the laptop:\n" + names + "\n\nUse the buttons on a file in the chat to play or open it, or tap Download to save it in Downloads.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("View chat", null)
                    .show();
        }
    }

    private void runChatAction(ChatActions.Action action, JSONObject attachment) {
        String id = attachment.optString("id", "");
        if (!EdgeInbox.validId(id)) {
            Toast.makeText(this, "This chat file has an invalid local name.", Toast.LENGTH_LONG).show();
            return;
        }
        String name = attachment.optString("name", EdgeInbox.name(id));
        String mime = ChatActions.playerMime(attachment.optString("mime", ""), name);
        android.net.Uri uri = receivedUri(id);
        switch (action) {
            case PLAY:
                openVideoWithChoice(uri, mime, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                break;
            case PLAY_IN_VLC:
                playInVlc(uri, mime);
                break;
            case DOWNLOAD:
                saveChatAttachmentToDownloads(attachment);
                break;
            default:
                openChatAttachment(attachment);
        }
    }

    private android.net.Uri receivedUri(String id) {
        return new android.net.Uri.Builder().scheme("content")
                .authority(getPackageName() + ".received").appendPath(id).build();
    }

    /** Sends the stream to VLC; without VLC installed, Android's chooser offers any player. */
    private void playInVlc(android.net.Uri stream, String mime) {
        Intent vlc = new Intent(Intent.ACTION_VIEW).setDataAndType(stream, mime)
                .setPackage(ChatActions.VLC_PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(vlc);
        } catch (android.content.ActivityNotFoundException noVlc) {
            Toast.makeText(this, "VLC is not installed. Choose another player.", Toast.LENGTH_SHORT).show();
            openVideoWithChoice(stream, mime, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    /** Copies a chat file into system Downloads with a progress notification. */
    private void saveChatAttachmentToDownloads(JSONObject attachment) {
        String id = attachment.optString("id", "");
        java.io.File inbox = new java.io.File(getFilesDir(), "received");
        java.io.File source = new java.io.File(inbox, id);
        String name = attachment.optString("name", EdgeInbox.name(id));
        String mime = MediaTypeClassifier.mimeType(name, attachment.optString("mime", ""));
        try {
            if (EdgeInbox.resolve(inbox, id) == null) {
                throw new java.io.IOException("The received file is no longer available.");
            }
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Saving " + name + " to Downloads…", Toast.LENGTH_SHORT).show();
        background.execute(() -> {
            DownloadPublisher.Notifier notifier = new DownloadPublisher.Notifier(this, LaptopFileTransfer.safeName(name));
            try {
                android.net.Uri saved = DownloadPublisher.publish(this, source, name, mime, notifier.stage("Saving"));
                notifier.done(saved, mime);
                runOnUiThread(() -> Toast.makeText(this, "Saved " + name + " to Downloads", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                String message = valueOr(error.getMessage(), "Could not save to Downloads.");
                notifier.failed(message);
                // Last resort: let the user pick a folder themselves.
                runOnUiThread(() -> {
                    Toast.makeText(this, message + " Choose where to save it instead.", Toast.LENGTH_LONG).show();
                    requestSaveChatAttachment(attachment);
                });
            }
        });
    }

    private void requestSaveChatAttachment(JSONObject attachment) {
        String id = attachment.optString("id", "");
        if (!EdgeInbox.validId(id)) {
            Toast.makeText(this, "This chat file has an invalid local name.", Toast.LENGTH_LONG).show();
            return;
        }
        java.io.File inbox = new java.io.File(getFilesDir(), "received");
        java.io.File source = new java.io.File(inbox, id);
        try {
            if (EdgeInbox.resolve(inbox, id) == null) {
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

    private void choosePhoneFiles(int request) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(picker, request); }
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
        if (!EdgeInbox.validId(id)) return;
        android.net.Uri uri = new android.net.Uri.Builder().scheme("content")
                .authority(getPackageName() + ".received").appendPath(id).build();
        String mime = MediaTypeClassifier.mimeType(attachment.optString("name", EdgeInbox.name(id)), attachment.optString("mime", ""));
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(view, "Open with")); }
        catch (Exception unavailable) { Toast.makeText(this, "No installed app can open this file type.", Toast.LENGTH_SHORT).show(); }
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
            String action = pendingCameraAction;
            pendingCameraAction = "";
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (!action.isEmpty()) runCameraAction(action);
            } else {
                // Without this phone's camera permission the call cannot be accepted.
                if ("accept".equals(action)) declineCameraCall();
                if (cameraStatus != null) cameraStatus.setText("Camera permission was not granted, so the call was not started. You can enable it in Android Settings.");
            }
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
        if (requestCode == REQUEST_SEND_FILE || requestCode == REQUEST_LIBRARY_FILES) {
            if (resultCode == RESULT_OK && data != null) {
                List<android.net.Uri> selected = new ArrayList<>();
                android.content.ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        android.net.Uri uri = clip.getItemAt(i).getUri();
                        if (uri != null) selected.add(uri);
                    }
                } else if (data.getData() != null) selected.add(data.getData());
                if (requestCode == REQUEST_LIBRARY_FILES) {
                    MediaRepository library = pickerLibrary;
                    pickerLibrary = null;
                    if (library == null || library != MediaHttpServer.activeLibrary()) {
                        Toast.makeText(this, "The session changed. Select the files again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    phoneUploadQueue.execute(() -> {
                        int added = 0;
                        for (android.net.Uri uri : selected) {
                            try { library.addDocument(uri); added++; }
                            catch (Exception unavailable) { android.util.Log.w("PocketBridge", "Selected document unavailable", unavailable); }
                        }
                        String result = added + " of " + selected.size() + " available. Open Files in Edge.";
                        runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
                    });
                    return;
                }
                chooseSendTarget(selected);
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


    // ---- Send to the laptop helper or another phone: resumable, parallel, cancellable ----

    /** The saved Windows helper link when it parses as a LAN link, else null. */
    private HelperLink savedHelperLinkOrNull() {
        String raw = getSavedHelperLink();
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            HelperLink link = HelperLink.parse(raw);
            link.requireLanAddress();
            return link;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /**
     * Where the picked files go: the laptop helper (resumable upload to its
     * PocketBridge folder), the other phone (its inbox), or, as before, this
     * phone's shared inbox for Edge to download. Without a helper link or a
     * connected phone the old behaviour runs directly.
     */
    private void chooseSendTarget(List<android.net.Uri> selected) {
        if (selected.isEmpty()) return;
        HelperLink helper = savedHelperLinkOrNull();
        PeerCameraLink.Target peer = PeerCameraLink.INSTANCE.target();
        Runnable keepHere = () -> {
            for (android.net.Uri uri : selected) savePhoneFileForLaptop(uri);
            if (selected.size() > 1) Toast.makeText(this, "Queued " + selected.size() + " files in order.", Toast.LENGTH_SHORT).show();
        };
        if (helper == null && peer == null) { keepHere.run(); return; }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (helper != null) {
            labels.add("Laptop (Windows helper at " + helper.host + ")");
            actions.add(() -> sendToReceiver(selected, "http://" + helper.host + ":" + helper.port, null, helper.token, false, "the laptop"));
        }
        if (peer != null) {
            labels.add("Other phone (" + addressFrom(peer.origin) + ")");
            actions.add(() -> sendToReceiver(selected, peer.origin, peer.code, null, true, "the other phone"));
        }
        labels.add("Keep on this phone for Edge to download");
        actions.add(keepHere);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Send " + (selected.size() == 1 ? "1 file" : selected.size() + " files") + " to")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A picked document as an upload source: positional reads through its descriptor. */
    private static final class UriSource implements ChunkedUploader.Source {
        final android.content.ContentResolver resolver;
        final android.net.Uri uri;
        final String name;
        final long size;
        final long lastModified;
        UriSource(android.content.ContentResolver resolver, android.net.Uri uri, String name, long size, long lastModified) {
            this.resolver = resolver; this.uri = uri; this.name = name; this.size = size; this.lastModified = lastModified;
        }
        @Override public String name() { return name; }
        @Override public long size() { return size; }
        @Override public long lastModified() { return lastModified; }
        @SuppressWarnings("resource")
        @Override public java.nio.channels.FileChannel open() throws java.io.IOException {
            android.os.ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) throw new java.io.IOException("Android could not open " + name + ".");
            // Closing the channel closes the stream, which closes the descriptor.
            return new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).getChannel();
        }
    }

    /**
     * Reads name, size and lastModified (part of the resume key). A document
     * that cannot be read by position (a pipe from a cloud provider, unknown
     * size) is copied to the cache first and sent from there.
     */
    private ChunkedUploader.Source sourceFor(android.net.Uri uri) throws java.io.IOException {
        android.content.ContentResolver resolver = getContentResolver();
        String name = "shared-file";
        long size = -1, modified = 0;
        try (android.database.Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                int modifiedColumn = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (nameColumn >= 0 && cursor.getString(nameColumn) != null) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) modified = cursor.getLong(modifiedColumn);
            }
        }
        long statSize = -1;
        try (android.os.ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor != null) statSize = descriptor.getStatSize();
        }
        if (statSize >= 0) return new UriSource(resolver, uri, name, statSize, modified);
        // Not seekable: copy once, then send the copy (still resumable by its key).
        java.io.File folder = new java.io.File(getCacheDir(), "outgoing");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Could not prepare " + name + ".");
        java.io.File copy = new java.io.File(folder, UploadSessions.sessionId(uri + "\n" + size + "\n" + modified) + ".bin");
        if (!copy.isFile() || (size >= 0 && copy.length() != size)) {
            try (java.io.InputStream input = resolver.openInputStream(uri);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(copy)) {
                if (input == null) throw new java.io.IOException("Android could not open " + name + ".");
                byte[] buffer = new byte[1024 * 1024];
                for (int read; (read = input.read(buffer)) != -1; ) output.write(buffer, 0, read);
            }
        }
        final String finalName = name;
        final long finalModified = modified;
        return new ChunkedUploader.Source() {
            @Override public String name() { return finalName; }
            @Override public long size() { return copy.length(); }
            @Override public long lastModified() { return finalModified; }
            @SuppressWarnings("resource")
            @Override public java.nio.channels.FileChannel open() throws java.io.IOException { return new java.io.FileInputStream(copy).getChannel(); }
        };
    }

    private android.app.AlertDialog sendDialog;

    /**
     * Sends the picked files with ChunkedUploader: probe first (an older
     * receiver answers 404), small files in one PBB1 batch, big ones as parallel
     * chunks; progress with MB/s, Cancel, and Retry that resumes.
     */
    private void sendToReceiver(List<android.net.Uri> uris, String base, String code, String token, boolean phonePeer, String label) {
        ChunkedUploader uploader = new ChunkedUploader(base, code, token);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView line = new TextView(this);
        line.setText("Preparing…");
        line.setTextColor(COLOR_INK);
        box.addView(bar, matchWrap());
        box.addView(line, matchWrap());
        if (sendDialog != null) sendDialog.dismiss();
        sendDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Sending to " + label)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (dialog, which) -> background.execute(uploader::stop))
                .show();
        android.app.AlertDialog shown = sendDialog;
        long startedAt = System.nanoTime();
        phoneUploadQueue.execute(() -> {
            long[] lastUi = {0};
            try {
                boolean resumable = uploader.probe();
                if (!resumable) {
                    if (!phonePeer) {
                        // An older helper has no upload route: keep the old way.
                        runOnUiThread(() -> {
                            shown.dismiss();
                            Toast.makeText(this, "The laptop helper is an older version. The files are in this phone's shared inbox for Edge instead; update the helper for direct sending.", Toast.LENGTH_LONG).show();
                            for (android.net.Uri uri : uris) savePhoneFileForLaptop(uri);
                        });
                        return;
                    }
                }
                List<ChunkedUploader.Source> sources = new ArrayList<>();
                long total = 0;
                for (android.net.Uri uri : uris) { ChunkedUploader.Source source = sourceFor(uri); sources.add(source); total += source.size(); }
                final long grand = total;
                long[] finished = {0};
                ChunkedUploader.Progress progress = (done, ignoredTotal) -> {
                    long now = System.nanoTime();
                    if (now - lastUi[0] < 250_000_000L && finished[0] + done < grand) return;
                    lastUi[0] = now;
                    long all = Math.min(grand, finished[0] + done);
                    double seconds = Math.max(0.001, (now - startedAt) / 1e9);
                    String text = String.format(java.util.Locale.ROOT, "%s of %s · %.1f MB/s",
                            android.text.format.Formatter.formatShortFileSize(this, all),
                            android.text.format.Formatter.formatShortFileSize(this, grand), all / 1e6 / seconds);
                    runOnUiThread(() -> { bar.setProgress(grand == 0 ? 1000 : (int) (all * 1000 / grand)); line.setText(text); });
                };
                boolean legacy = !resumable;
                // Small files together in one batch (current receivers only).
                List<ChunkedUploader.Source> small = new ArrayList<>(), large = new ArrayList<>();
                for (ChunkedUploader.Source source : sources) (source.size() <= TransferPlan.SMALL_FILE ? small : large).add(source);
                if (!legacy && small.size() > 1) {
                    List<String> paths = new ArrayList<>();
                    Set<String> used = new HashSet<>();
                    for (ChunkedUploader.Source source : small) {
                        String name = UploadSessions.safeName(source.name()), candidate = name;
                        for (int n = 2; !used.add(candidate.toLowerCase(java.util.Locale.ROOT)); n++) candidate = n + "-" + name;
                        paths.add(candidate);
                    }
                    long smallBytes = 0;
                    for (ChunkedUploader.Source source : small) smallBytes += source.size();
                    final long batchBytes = smallBytes;
                    uploader.uploadBatchSources(small, paths, (done, length) -> progress.update(Math.min(batchBytes, done), grand));
                    finished[0] += smallBytes;
                    small.clear();
                }
                large.addAll(0, small);
                for (ChunkedUploader.Source source : large) {
                    runOnUiThread(() -> shown.setTitle("Sending " + source.name()));
                    if (legacy) uploader.uploadLegacy(source, progress);
                    else uploader.send(source, "", progress, phonePeer);
                    finished[0] += source.size();
                }
                double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1e9);
                String done = String.format(java.util.Locale.ROOT, "Sent %d file%s to %s (%.1f MB/s).",
                        sources.size(), sources.size() == 1 ? "" : "s", label, grand / 1e6 / seconds);
                ChatStore.append(this, done, "phone");
                runOnUiThread(() -> { shown.dismiss(); Toast.makeText(this, done, Toast.LENGTH_LONG).show(); });
            } catch (Exception error) {
                boolean cancelled = uploader.stopped();
                String message = cancelled ? "Cancelled. Send the same files again to continue where they stopped."
                        : (error.getMessage() == null ? "The transfer stopped." : error.getMessage());
                runOnUiThread(() -> {
                    shown.dismiss();
                    if (isFinishing()) return;
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                            .setTitle(cancelled ? "Transfer cancelled" : "Transfer stopped")
                            .setMessage(message)
                            .setNegativeButton("Close", null);
                    // Same files, same keys: the receiver reports what it has, so this resumes.
                    builder.setPositiveButton(cancelled ? "Resume" : "Retry (resumes)",
                            (dialog, which) -> sendToReceiver(uris, base, code, token, phonePeer, label));
                    builder.show();
                });
            }
        });
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
