package com.pocketbridge;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Optional helper for the phone's airplane-mode reset. When the user enables
 * "PocketBridge airplane reset" under Settings → Accessibility, this service
 * opens Quick Settings and taps the Airplane mode or Hotspot tile, exactly as
 * the user would. It reads nothing else and acts only when a reset was asked
 * for. Tiles must be on the first Quick Settings page; tile labels differ by
 * vendor and language, so the patterns below cover common English names and
 * the caller always checks the real state afterwards.
 */
public final class AirplaneAccessibilityService extends AccessibilityService {
    static final Pattern TILE_AIRPLANE = Pattern.compile("(?is).*(airplane|aeroplane|flight mode|plane mode).*");
    static final Pattern TILE_HOTSPOT = Pattern.compile("(?is).*(hotspot|tethering|wi-?fi hotspot|personal hotspot).*");

    private static volatile AirplaneAccessibilityService instance;

    static boolean isConnected() { return instance != null; }

    @Override protected void onServiceConnected() { instance = this; }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    /**
     * Opens Quick Settings, taps the first tile whose label matches, closes
     * the shade, and reports whether {@code state} reached {@code wanted}.
     * Blocks the calling (background) thread for a few seconds.
     */
    static boolean toggleTile(Pattern tile, boolean wanted, AirplaneController.Check state) throws InterruptedException {
        AirplaneAccessibilityService service = instance;
        if (service == null) return false;
        if (state.ok() == wanted) return true;
        service.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        Thread.sleep(900);
        boolean clicked = false;
        for (int attempt = 0; attempt < 3 && !clicked; attempt++) {
            clicked = service.clickMatching(tile);
            if (!clicked) Thread.sleep(500);
        }
        boolean reached = clicked && AirplaneController.waitFor(() -> state.ok() == wanted, 6000);
        if (Build.VERSION.SDK_INT >= 31) service.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else { service.performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(200); service.performGlobalAction(GLOBAL_ACTION_BACK); }
        return reached;
    }

    private boolean clickMatching(Pattern tile) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && clickIn(root, tile, 0)) return true;
                }
            }
            AccessibilityNodeInfo active = getRootInActiveWindow();
            return active != null && clickIn(active, tile, 0);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean clickIn(AccessibilityNodeInfo node, Pattern tile, int depth) {
        if (node == null || depth > 40) return false;
        if (matches(node, tile)) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickIn(node.getChild(i), tile, depth + 1)) return true;
        }
        return false;
    }

    private static boolean matches(AccessibilityNodeInfo node, Pattern tile) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String label = ((text == null ? "" : text) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT).trim();
        return !label.isEmpty() && tile.matcher(label).matches();
    }
}
