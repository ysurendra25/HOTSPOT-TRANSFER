package com.pocketbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Full-screen, step-by-step airplane-mode reset for the PHONE, used when the
 * app cannot switch airplane mode itself:
 *   1. turn airplane mode ON, 2. turn it OFF, 3. turn the hotspot back ON.
 * Each step opens the right Settings screen and advances by itself as soon as
 * the phone reports the change; the laptop reconnects on its own when the
 * hotspot is back.
 */
public final class AirplaneCycleActivity extends Activity {
    static final String EXTRA_STEP = "step";
    private static final int INK = Color.rgb(232, 241, 244);
    private static final int MUTED = Color.rgb(157, 178, 187);
    private static final int GREEN = Color.rgb(40, 177, 140);
    private static final int SOFT = Color.rgb(24, 48, 54);

    private enum Step { AIRPLANE_ON, AIRPLANE_OFF, HOTSPOT, DONE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Step step = Step.AIRPLANE_ON;
    private TextView title;
    private TextView detail;
    private TextView progress;
    private Button action;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            advance();
            if (step != Step.DONE) handler.postDelayed(this, 700);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String first = getIntent().getStringExtra(EXTRA_STEP);
        if ("hotspot".equals(first)) step = Step.HOTSPOT;
        else if (AirplaneController.isAirplaneOn(this)) step = Step.AIRPLANE_OFF;
        buildUi();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("hotspot".equals(intent.getStringExtra(EXTRA_STEP))) step = Step.HOTSPOT;
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override protected void onPause() {
        // Keep checking while Settings is in front, so the step is already
        // advanced when the user comes back.
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private void advance() {
        Step before = step;
        boolean airplane = AirplaneController.isAirplaneOn(this);
        if (step == Step.AIRPLANE_ON && airplane) step = Step.AIRPLANE_OFF;
        if (step == Step.AIRPLANE_OFF && !airplane && before == Step.AIRPLANE_OFF) step = Step.HOTSPOT;
        if (step == Step.HOTSPOT && !airplane && AirplaneController.isHotspotOn(this)) {
            step = Step.DONE;
            AirplaneController.noteGuidedResult("Airplane mode switched on and off; the hotspot is back.");
            AirplaneController.finishCycle("done");
            AirplaneController.clearGuide(this);
        }
        if (step != before) {
            render();
            // Bring this screen back over Settings so the next step is visible.
            if (step != Step.DONE || before == Step.HOTSPOT) {
                Intent front = new Intent(this, AirplaneCycleActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                try { startActivity(front); } catch (Exception ignored) { }
            }
        }
    }

    private void render() {
        switch (step) {
            case AIRPLANE_ON:
                setText("1 of 3 · Turn airplane mode ON (phone)",
                        "This refreshes the phone's mobile data. The hotspot and the laptop disconnect for a moment and come back.",
                        "Open airplane mode settings");
                break;
            case AIRPLANE_OFF:
                setText("2 of 3 · Now turn airplane mode OFF (phone)",
                        "Airplane mode is on. Switch it off again; mobile data reconnects with a fresh session.",
                        "Open airplane mode settings");
                break;
            case HOTSPOT:
                setText("3 of 3 · Turn the hotspot back ON",
                        "Android switched the hotspot off with airplane mode. Turn \u201cHotspot\u201d on again; the laptop rejoins it by itself.",
                        "Open hotspot settings");
                break;
            case DONE:
                setText("Done \u2713 Hotspot is back",
                        "Mobile data was refreshed and the hotspot is on. The laptop reconnects automatically.",
                        "Close");
                break;
        }
        boolean airplane = AirplaneController.isAirplaneOn(this);
        boolean hotspot = AirplaneController.isHotspotOn(this);
        progress.setText("Phone airplane mode: " + (airplane ? "ON" : "OFF") + "   ·   Phone hotspot: " + (hotspot ? "ON" : "OFF")
                + "\nAutomatic control: " + AirplaneController.method(this));
    }

    private void setText(String heading, String body, String button) {
        title.setText(heading);
        detail.setText(body);
        action.setText(button);
    }

    private void onAction() {
        switch (step) {
            case AIRPLANE_ON:
            case AIRPLANE_OFF:
                if (!AirplaneController.openAirplaneSettings(this)) Toast.makeText(this, "Use the Airplane mode tile in Quick Settings.", Toast.LENGTH_LONG).show();
                break;
            case HOTSPOT:
                if (!AirplaneController.openHotspotSettings(this)) Toast.makeText(this, "Use the Hotspot tile in Quick Settings.", Toast.LENGTH_LONG).show();
                break;
            case DONE:
                finish();
                break;
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(9, 21, 25));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(48), dp(24), dp(24));
        scroll.addView(column);

        TextView label = text("PHONE · AIRPLANE MODE RESET", 12, GREEN);
        column.addView(label);
        title = text("", 24, INK);
        title.setPadding(0, dp(10), 0, dp(10));
        column.addView(title);
        detail = text("", 15, MUTED);
        column.addView(detail);
        action = new Button(this);
        action.setAllCaps(false);
        action.setTextColor(Color.WHITE);
        action.setBackgroundColor(GREEN);
        action.setOnClickListener(v -> onAction());
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(-1, dp(56));
        big.topMargin = dp(24);
        column.addView(action, big);
        progress = text("", 13, MUTED);
        progress.setPadding(0, dp(16), 0, 0);
        column.addView(progress);

        Button skip = new Button(this);
        skip.setAllCaps(false);
        skip.setText("Cancel");
        skip.setTextColor(INK);
        skip.setBackgroundColor(SOFT);
        skip.setOnClickListener(v -> {
            AirplaneController.noteGuidedResult("Cancelled on the phone.");
            AirplaneController.finishCycle("cancelled");
            AirplaneController.clearGuide(this);
            finish();
        });
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(-1, dp(48));
        small.topMargin = dp(12);
        column.addView(skip, small);

        TextView auto = text("Make it automatic: enable \u201cPocketBridge airplane reset\u201d in Accessibility (taps the Airplane and Hotspot tiles for you), "
                + "or grant the airplane setting once from a computer:\n" + AirplaneController.ADB_GRANT, 12, MUTED);
        auto.setPadding(0, dp(24), 0, dp(8));
        column.addView(auto);
        Button accessibility = new Button(this);
        accessibility.setAllCaps(false);
        accessibility.setText("Open Accessibility settings");
        accessibility.setTextColor(INK);
        accessibility.setBackgroundColor(SOFT);
        accessibility.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        column.addView(accessibility, small);
        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText("Copy the adb command");
        copy.setTextColor(INK);
        copy.setBackgroundColor(SOFT);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("adb", AirplaneController.ADB_GRANT));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-1, dp(48));
        copyLp.topMargin = dp(8);
        column.addView(copy, copyLp);
        column.setGravity(Gravity.START);
        setContentView(scroll);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
