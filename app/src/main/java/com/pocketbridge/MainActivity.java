package com.pocketbridge;

import android.Manifest;
import android.app.Activity;
import android.app.MediaProjectionManager;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_SCREEN_CAPTURE = 41;
    private static final int COLOR_INK = Color.rgb(26, 41, 54);
    private static final int COLOR_MUTED = Color.rgb(98, 116, 129);
    private static final int COLOR_GREEN = Color.rgb(14, 132, 105);
    private static final int COLOR_BG = Color.rgb(245, 248, 249);

    private TextView statusText;
    private TextView networkText;
    private TextView linkText;
    private Button startButton;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(ShareSessionService.EXTRA_STATUS);
            String ssid = intent.getStringExtra(ShareSessionService.EXTRA_SSID);
            String password = intent.getStringExtra(ShareSessionService.EXTRA_PASSWORD);
            String url = intent.getStringExtra(ShareSessionService.EXTRA_URL);
            if (status != null) statusText.setText(status);
            if (ssid != null || password != null) {
                networkText.setText("Wi-Fi name  " + valueOr(ssid, "starting…") + "\nPassword  " + valueOr(password, "starting…"));
            }
            if (url != null) linkText.setText(url);
            boolean ready = url != null && !url.isEmpty();
            startButton.setText(ready ? "Stop local share" : "Start local share");
            startButton.setTag(ready);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ShareSessionService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
        sendServiceCommand(ShareSessionService.ACTION_QUERY);
    }

    @Override protected void onStop() {
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
        header.setPadding(dp(24), dp(30), dp(24), dp(18));
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
        title.setTextSize(29);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(10), 0, dp(7));
        header.addView(title);
        TextView intro = new TextView(this);
        intro.setText("Your phone creates a private Wi-Fi link for your laptop. Files stay on this network.");
        intro.setTextColor(COLOR_MUTED);
        intro.setTextSize(15);
        header.addView(intro);
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(24));

        statusText = new TextView(this);
        statusText.setText("Ready when you are");
        statusText.setTextColor(COLOR_INK);
        statusText.setTextSize(17);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addCard(content, "SESSION", statusText);

        networkText = new TextView(this);
        networkText.setText("Start a share to create a local-only hotspot.");
        networkText.setTextColor(COLOR_INK);
        networkText.setTextSize(15);
        networkText.setLineSpacing(dp(5), 1f);
        addCard(content, "CONNECT YOUR LAPTOP", networkText);

        linkText = new TextView(this);
        linkText.setText("The browser link appears here.");
        linkText.setTextColor(COLOR_GREEN);
        linkText.setTextSize(16);
        linkText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        addCard(content, "OPEN THIS LINK ON THE LAPTOP", linkText);

        startButton = button("Start local share", COLOR_GREEN);
        startButton.setOnClickListener(v -> {
            Object running = startButton.getTag();
            if (Boolean.TRUE.equals(running)) sendServiceCommand(ShareSessionService.ACTION_STOP);
            else ensurePermissionsAndStart();
        });
        content.addView(startButton, matchWrap());

        Button copyButton = button("Copy browser link", Color.rgb(226, 237, 235));
        copyButton.setTextColor(COLOR_GREEN);
        copyButton.setOnClickListener(v -> {
            String url = linkText.getText().toString();
            if (url.startsWith("http://")) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("PocketBridge link", url));
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
            } else Toast.makeText(this, "Start a local share first", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams buttonGap = matchWrap();
        buttonGap.topMargin = dp(10);
        content.addView(copyButton, buttonGap);

        Button screenButton = button("Share phone screen", Color.rgb(31, 53, 66));
        screenButton.setOnClickListener(v -> {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        });
        LinearLayout.LayoutParams screenGap = matchWrap();
        screenGap.topMargin = dp(18);
        content.addView(screenButton, screenGap);

        TextView note = new TextView(this);
        note.setText("For screen sharing, Android will ask you to approve capture each time. Keep this app open while the share is active.");
        note.setTextColor(COLOR_MUTED);
        note.setTextSize(13);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(note);

        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);
    }

    private void addCard(LinearLayout parent, String label, TextView content) {
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

    private void ensurePermissionsAndStart() {
        List<String> required = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(required, Manifest.permission.NEARBY_WIFI_DEVICES);
            addIfMissing(required, Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(required, Manifest.permission.READ_MEDIA_VIDEO);
            addIfMissing(required, Manifest.permission.READ_MEDIA_AUDIO);
            addIfMissing(required, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addIfMissing(required, Manifest.permission.ACCESS_FINE_LOCATION);
            addIfMissing(required, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!required.isEmpty()) requestPermissions(required.toArray(new String[0]), REQUEST_PERMISSIONS);
        else sendServiceCommand(ShareSessionService.ACTION_START);
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) permissions.add(permission);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean nearbyAllowed = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            boolean locationAllowed = Build.VERSION.SDK_INT >= 33 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (nearbyAllowed && locationAllowed) sendServiceCommand(ShareSessionService.ACTION_START);
            else statusText.setText("Nearby Wi-Fi permission is needed to start the private hotspot.");
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
                Toast.makeText(this, "Screen sharing is ready. Choose Watch screen on the laptop.", Toast.LENGTH_LONG).show();
            } else Toast.makeText(this, "Screen sharing was cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendServiceCommand(String action) {
        Intent service = new Intent(this, ShareSessionService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26 && ShareSessionService.ACTION_START.equals(action)) startForegroundService(service);
        else startService(service);
    }

    private String valueOr(String value, String fallback) { return value == null || value.isEmpty() ? fallback : value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
}
