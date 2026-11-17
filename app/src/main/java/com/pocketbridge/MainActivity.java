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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_SCREEN_CAPTURE = 41;
    private static final int COLOR_INK = Color.rgb(26, 41, 54);
    private static final int COLOR_MUTED = Color.rgb(98, 116, 129);
    private static final int COLOR_GREEN = Color.rgb(14, 132, 105);
    private static final int COLOR_BG = Color.rgb(245, 248, 249);
    private static final int COLOR_SOFT = Color.rgb(226, 237, 235);

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
    private boolean receiverRegistered;
    private boolean sharing;

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
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(28), dp(24), dp(16));
        header.setBackgroundColor(Color.WHITE);
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
        tabHost.addView(connectScroll);
        tabHost.addView(screenScroll);
        tabHost.addView(libraryScroll);
        tabHost.addView(chatScroll);
        screenScroll.setVisibility(View.GONE);
        libraryScroll.setVisibility(View.GONE);
        chatScroll.setVisibility(View.GONE);
        this.tabViews = new ScrollView[]{connectScroll, screenScroll, libraryScroll, chatScroll};

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

        EditText laptopAddress = new EditText(this);
        laptopAddress.setSingleLine(true);
        laptopAddress.setHint("http://laptop-address:7070/?t=code");
        laptopAddress.setTextSize(14);
        addCard(content, "BROWSE LAPTOP FILES", laptopAddress);
        TextView laptopStatus = new TextView(this);
        laptopStatus.setText("Start the portable Windows helper, then paste its local link here.");
        laptopStatus.setTextColor(COLOR_MUTED);
        laptopStatus.setTextSize(13);
        LinearLayout laptopResults = new LinearLayout(this);
        laptopResults.setOrientation(LinearLayout.VERTICAL);
        boolean[] showLaptopRecents = {false};
        EditText laptopSearch = new EditText(this);
        laptopSearch.setSingleLine(true);
        laptopSearch.setHint("Search laptop files");
        laptopSearch.setTextSize(14);
        laptopSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = 0; i < laptopResults.getChildCount(); i++) {
                    View row = laptopResults.getChildAt(i);
                    Object tag = row.getTag();
                    String name = tag instanceof String ? (String) tag : "";
                    boolean recent = getPreferences(MODE_PRIVATE).getStringSet("laptop_recent", java.util.Collections.emptySet()).contains(name);
                    row.setVisibility(name.toLowerCase(java.util.Locale.ROOT).contains(query) && (!showLaptopRecents[0] || recent) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        addCard(content, "SEARCH LAPTOP FILES", laptopSearch);
        Button browseLaptop = button("Load laptop files", COLOR_GREEN);
        Button recentLaptop = button("Recent files", COLOR_SOFT);
        recentLaptop.setTextColor(COLOR_INK);
        recentLaptop.setOnClickListener(v -> {
            showLaptopRecents[0] = !showLaptopRecents[0];
            recentLaptop.setText(showLaptopRecents[0] ? "Show all laptop files" : "Recent files");
            String query = laptopSearch.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            java.util.Set<String> recent = getPreferences(MODE_PRIVATE).getStringSet("laptop_recent", java.util.Collections.emptySet());
            for (int i = 0; i < laptopResults.getChildCount(); i++) {
                View row = laptopResults.getChildAt(i);
                String name = row.getTag() instanceof String ? (String) row.getTag() : "";
                row.setVisibility(name.toLowerCase(java.util.Locale.ROOT).contains(query) && (!showLaptopRecents[0] || recent.contains(name)) ? View.VISIBLE : View.GONE);
            }
        });
        browseLaptop.setOnClickListener(v -> {
            String text = laptopAddress.getText().toString().trim();
            if (text.isEmpty()) { laptopStatus.setText("Paste the laptop helper link first."); return; }
            browseLaptop.setEnabled(false);
            laptopStatus.setText("Connecting over the local network…");
            laptopResults.removeAllViews();
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    android.net.Uri supplied = android.net.Uri.parse(text);
                    String token = supplied.getQueryParameter("t");
                    if (token == null || !"http".equalsIgnoreCase(supplied.getScheme())) throw new Exception("Use the full local http link printed by the Windows helper.");
                    android.net.Uri filesUri = supplied.buildUpon().path("/files").clearQuery().appendQueryParameter("t", token).build();
                    connection = (HttpURLConnection) new URL(filesUri.toString()).openConnection();
                    connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
                    if (connection.getResponseCode() != 200) throw new Exception("Laptop helper returned HTTP " + connection.getResponseCode());
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    try (java.io.InputStream stream = connection.getInputStream()) { while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count); }
                    JSONObject data = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                    JSONArray items = data.optJSONArray("files");
                    runOnUiThread(() -> {
                        if (items == null || items.length() == 0) laptopStatus.setText("No files found in the helper’s shared folder.");
                        else {
                            laptopStatus.setText(items.length() + " laptop files. Tap one to open/download.");
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null) continue;
                                String name = item.optString("name", "File");
                                String relative = item.optString("url", "");
                                android.net.Uri fileUri = supplied.buildUpon().path(relative).clearQuery().appendQueryParameter("t", token).build();
                                Button row = button(name + " · " + android.text.format.Formatter.formatShortFileSize(this, item.optLong("size")), COLOR_SOFT);
                                row.setTextColor(COLOR_INK);
                                row.setTag(name);
                                row.setOnClickListener(open -> {
                                    java.util.Set<String> recent = new java.util.HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("laptop_recent", java.util.Collections.emptySet()));
                                    recent.add(name);
                                    getPreferences(MODE_PRIVATE).edit().putStringSet("laptop_recent", recent).apply();
                                    startActivity(new Intent(Intent.ACTION_VIEW, fileUri));
                                });
                                laptopResults.addView(row, matchWrap());
                            }
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> laptopStatus.setText("Could not connect: " + error.getMessage()));
                } finally { if (connection != null) connection.disconnect(); runOnUiThread(() -> browseLaptop.setEnabled(true)); }
            }, "PocketBridge-laptop-files").start();
        });
        content.addView(browseLaptop, matchWrap());
        content.addView(recentLaptop, matchWrap());
        content.addView(laptopStatus, matchWrap());
        content.addView(laptopResults, matchWrap());

        // Laptop screen on the phone: the Windows helper already streams JPEG over
        // the hotspot, so show its own live page here instead of duplicating it.
        android.webkit.WebView laptopView = new android.webkit.WebView(this);
        laptopView.getSettings().setJavaScriptEnabled(true);
        laptopView.getSettings().setLoadWithOverviewMode(true);
        laptopView.getSettings().setUseWideViewPort(true);
        laptopView.setBackgroundColor(Color.BLACK);
        laptopView.setVisibility(View.GONE);
        laptopView.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(240)));
        Button showLaptop = button("Show laptop screen here", COLOR_GREEN);
        showLaptop.setOnClickListener(v -> {
            String link = laptopAddress.getText().toString().trim();
            if (link.isEmpty()) {
                Toast.makeText(this, "Paste the helper link above first.", Toast.LENGTH_SHORT).show();
                return;
            }
            laptopView.setVisibility(View.VISIBLE);
            laptopView.loadUrl(link);
        });
        Button hideLaptop = button("Hide laptop screen", COLOR_SOFT);
        hideLaptop.setTextColor(COLOR_INK);
        hideLaptop.setOnClickListener(v -> { laptopView.setVisibility(View.GONE); laptopView.loadUrl("about:blank"); });
        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams liveLeft = new LinearLayout.LayoutParams(0, -2, 1f);
        liveLeft.rightMargin = dp(6);
        LinearLayout.LayoutParams liveRight = new LinearLayout.LayoutParams(0, -2, 1f);
        liveRight.leftMargin = dp(6);
        liveRow.addView(showLaptop, liveLeft);
        liveRow.addView(hideLaptop, liveRight);
        addCard(content, "LAPTOP SCREEN ON THIS PHONE", liveRow);
        content.addView(laptopView, matchWrap());

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

        page.addView(tabHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        page.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
        showTab(0);
    }

    private ScrollView[] tabViews;
    private Button[] tabButtons;
    private LinearLayout libraryList;
    private TextView libraryStatus;
    private String libraryFilter = "all";
    private LinearLayout chatList;
    private TextView chatStatus;
    private EditText chatInput;
    private final android.os.Handler chatHandler = new android.os.Handler(android.os.Looper.getMainLooper());
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

    /** The bottom bar real apps use: four icons, the chosen one highlighted. */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(4), dp(8), dp(4), dp(10));
        bar.setElevation(dp(8));
        int[] icons = {R.drawable.ic_tab_connect, R.drawable.ic_tab_screen, R.drawable.ic_tab_library, R.drawable.ic_tab_chat};
        String[] labels = {"Connect", "Screen", "Library", "Chat"};
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
        if (index == 2) refreshLibrary();
        if (index == 3) {
            refreshChat();
            chatHandler.removeCallbacks(chatPoll);
            chatHandler.postDelayed(chatPoll, 2500);
        } else {
            chatHandler.removeCallbacks(chatPoll);
        }
        if (index == 1) refreshScreenButton();
    }

    /** Library tab: what is on this phone, newest first, tap to open. */
    private void buildLibraryTab(LinearLayout parent) {
        libraryStatus = new TextView(this);
        libraryStatus.setText("Reading your media…");
        libraryStatus.setTextColor(COLOR_INK);
        libraryStatus.setTextSize(14);
        libraryStatus.setLineSpacing(dp(3), 1f);
        addCard(parent, "ON THIS PHONE", libraryStatus);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        String[] kinds = {"all", "image", "video", "audio", "file"};
        String[] labels = {"All", "Photos", "Videos", "Audio", "Files"};
        for (int i = 0; i < kinds.length; i++) {
            final String kind = kinds[i];
            Button chip = button(labels[i], "all".equals(kind) ? COLOR_GREEN : COLOR_SOFT);
            if (!"all".equals(kind)) chip.setTextColor(COLOR_INK);
            chip.setTextSize(12);
            chip.setMinHeight(dp(40));
            chip.setOnClickListener(v -> { libraryFilter = kind; refreshLibrary(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(4);
            filters.addView(chip, lp);
        }
        addCard(parent, "SHOW", filters);

        libraryList = new LinearLayout(this);
        libraryList.setOrientation(LinearLayout.VERTICAL);
        addCard(parent, "ON THIS PHONE", libraryList);

        // The same idea for the laptop: paste the helper link once, then search its
        // files from here instead of opening a second window on the phone.
        EditText laptopLink = new EditText(this);
        laptopLink.setSingleLine(true);
        laptopLink.setTextSize(14);
        laptopLink.setHint("http://laptop-address:7070/?t=code");
        addCard(parent, "ON THE LAPTOP", laptopLink);

        EditText laptopSearchField = new EditText(this);
        laptopSearchField.setSingleLine(true);
        laptopSearchField.setTextSize(14);
        laptopSearchField.setHint("Search laptop files");
        addCard(parent, "SEARCH LAPTOP FILES", laptopSearchField);

        TextView laptopStatusView = new TextView(this);
        laptopStatusView.setText("Paste the Windows helper link, then load the files.");
        laptopStatusView.setTextColor(COLOR_MUTED);
        laptopStatusView.setTextSize(12);
        laptopStatusView.setPadding(dp(4), 0, dp(4), dp(10));
        parent.addView(laptopStatusView);

        LinearLayout laptopRows = new LinearLayout(this);
        laptopRows.setOrientation(LinearLayout.VERTICAL);
        addCard(parent, "LAPTOP FILES", laptopRows);

        Button loadLaptop = button("Load laptop files", COLOR_GREEN);
        loadLaptop.setOnClickListener(v -> {
            String link = laptopLink.getText().toString().trim();
            if (link.isEmpty()) {
                laptopStatusView.setText("Paste the helper link first.");
                return;
            }
            laptopStatusView.setText("Reading the laptop folder…");
            laptopRows.removeAllViews();
            loadLaptop.setEnabled(false);
            fetchLaptopFiles(link, laptopRows, laptopStatusView, laptopSearchField.getText().toString().trim(), () -> loadLaptop.setEnabled(true));
        });
        LinearLayout.LayoutParams loadGap = matchWrap();
        loadGap.topMargin = dp(2);
        parent.addView(loadLaptop, loadGap);
        this.laptopFilter = laptopSearchField;
        this.laptopRowHolder = laptopRows;
        this.laptopStatus = laptopStatusView;
        this.laptopLinkField = laptopLink;
        laptopSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterLaptopRows(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
    }

    private EditText laptopFilter;
    private EditText laptopLinkField;
    private LinearLayout laptopRowHolder;
    private TextView laptopStatus;

    /** Hides laptop rows that do not match the search box. */
    private void filterLaptopRows() {
        if (laptopRowHolder == null || laptopFilter == null) return;
        String query = laptopFilter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < laptopRowHolder.getChildCount(); i++) {
            View row = laptopRowHolder.getChildAt(i);
            String name = row.getTag() instanceof String ? (String) row.getTag() : "";
            boolean matches = query.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(query);
            row.setVisibility(matches ? View.VISIBLE : View.GONE);
            if (matches) shown++;
        }
        if (laptopStatus != null && laptopRowHolder.getChildCount() > 0) {
            laptopStatus.setText(query.isEmpty()
                    ? shown + " laptop files. Tap one to open it."
                    : shown + " of " + laptopRowHolder.getChildCount() + " laptop files match.");
        }
    }

    /** Reads the helper's file list and fills the Library tab with it. */
    private void fetchLaptopFiles(String link, LinearLayout rows, TextView status, String initialQuery, Runnable done) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                android.net.Uri supplied = android.net.Uri.parse(link);
                String token = supplied.getQueryParameter("t");
                if (token == null || !"http".equalsIgnoreCase(supplied.getScheme())) throw new Exception("Use the full local http link printed by the Windows helper.");
                android.net.Uri filesUri = supplied.buildUpon().path("/files").clearQuery().appendQueryParameter("t", token).build();
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
                runOnUiThread(() -> {
                    if (items == null || items.length() == 0) {
                        status.setText("No files found in the helper's shared folder.");
                    } else {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;
                            String name = item.optString("name", "File");
                            String relative = item.optString("url", "");
                            android.net.Uri fileUri = supplied.buildUpon().path(relative).clearQuery().appendQueryParameter("t", token).build();
                            Button row = button(name + "\n" + android.text.format.Formatter.formatShortFileSize(this, item.optLong("size")), Color.WHITE);
                            row.setTextColor(COLOR_INK);
                            row.setTextSize(13);
                            row.setAllCaps(false);
                            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                            row.setTag(name);
                            row.setOnClickListener(open -> startActivity(new Intent(Intent.ACTION_VIEW, fileUri)));
                            LinearLayout.LayoutParams lp = matchWrap();
                            lp.topMargin = dp(4);
                            rows.addView(row, lp);
                        }
                        status.setText(items.length() + " laptop files. Tap one to open it.");
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

    private void refreshLibrary() {
        if (libraryList == null) return;
        final String kind = libraryFilter;
        libraryList.removeAllViews();
        libraryStatus.setText("Reading your media…");
        new Thread(() -> {
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
                libraryStatus.setText(status);
                for (MediaRepository.Item item : items) {
                    Button row = button(item.name + "\n" + android.text.format.Formatter.formatShortFileSize(this, item.size), Color.WHITE);
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
        }, "PocketBridge-library").start();
    }

    private void openMedia(MediaRepository.Item item) {
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
            TextView row = new TextView(this);
            row.setText(item.optString("text", ""));
            row.setTextSize(14);
            row.setTextColor(COLOR_INK);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable bubble = new android.graphics.drawable.GradientDrawable();
            bubble.setCornerRadius(dp(12));
            bubble.setColor(mine ? COLOR_SOFT : Color.WHITE);
            row.setBackground(bubble);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(6);
            lp.leftMargin = mine ? dp(36) : 0;
            lp.rightMargin = mine ? 0 : dp(36);
            chatList.addView(row, lp);
        }
    }

    private void addCard(LinearLayout parent, String label, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackgroundColor(Color.WHITE);
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
