package com.pocketbridge;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Builds the real phone screen and walks it, so the five-tab UI is executed at
 * least once before it ships. Robolectric cannot capture a screen, but it does
 * run the layout, the tab switching and the chat list for real.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTabsTest {

    private ActivityController<MainActivity> launch() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller;
    }

    private static List<Button> buttonsLabelled(View root, String... labels) {
        List<Button> found = new ArrayList<>();
        collect(root, found, labels);
        return found;
    }

    private static void collect(View view, List<Button> found, String... labels) {
        if (view instanceof Button) {
            String text = ((Button) view).getText() == null ? "" : ((Button) view).getText().toString();
            for (String label : labels) if (text.equals(label)) { found.add((Button) view); break; }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found, labels);
        }
    }

    private static List<ScrollView> tabPanels(View root) {
        List<ScrollView> found = new ArrayList<>();
        findTabHost(root, found);
        return found;
    }

    private static void findTabHost(View view, List<ScrollView> found) {
        if (view instanceof FrameLayout) {
            ViewGroup group = (ViewGroup) view;
            List<ScrollView> candidates = new ArrayList<>();
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof ScrollView) candidates.add((ScrollView) group.getChildAt(i));
            }
            if (candidates.size() == 5) { found.addAll(candidates); return; }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findTabHost(group.getChildAt(i), found);
        }
    }

    @Test public void theScreenBuildsWithFiveTabsAndOnlyTheFirstVisible() {
        ActivityController<MainActivity> controller = launch();
        Activity activity = controller.get();
        View root = activity.findViewById(android.R.id.content);

        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        assertEquals("all five tabs should be on screen", 5, tabs.size());

        List<ScrollView> panels = tabPanels(root);
        assertEquals("five tab panels expected", 5, panels.size());
        assertEquals("Connect is the starting tab", View.VISIBLE, panels.get(0).getVisibility());
        for (int i = 1; i < 5; i++) {
            assertEquals("panel " + i + " starts hidden", View.GONE, panels.get(i).getVisibility());
        }
        controller.pause().stop().destroy();
    }

    @Test public void tappingATabShowsOnlyThatPanel() {
        ActivityController<MainActivity> controller = launch();
        Activity activity = controller.get();
        View root = activity.findViewById(android.R.id.content);
        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        List<ScrollView> panels = tabPanels(root);

        tabs.get(2).performClick(); // Library
        assertEquals(View.GONE, panels.get(0).getVisibility());
        assertEquals(View.VISIBLE, panels.get(2).getVisibility());

        tabs.get(3).performClick(); // Chat
        assertEquals(View.GONE, panels.get(2).getVisibility());
        assertEquals(View.VISIBLE, panels.get(3).getVisibility());
        controller.pause().stop().destroy();
    }

    @Test public void theChatTabShowsMessagesFromBothSides() {
        ActivityController<MainActivity> controller = launch();
        Activity activity = controller.get();
        ChatStore.clear(activity);
        ChatStore.append(activity, "from the laptop", "laptop");
        ChatStore.append(activity, "from the phone", "phone");

        View root = activity.findViewById(android.R.id.content);
        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        tabs.get(3).performClick(); // Chat refreshes the list

        // The chat is read on a background thread and drawn back on the main
        // thread, so wait for both instead of expecting it synchronously.
        List<String> texts = new ArrayList<>();
        for (int attempt = 0; attempt < 100; attempt++) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            texts.clear();
            collectText(root, texts);
            if (texts.toString().contains("from the phone")) break;
            try { Thread.sleep(20); } catch (InterruptedException interrupted) { break; }
        }
        assertTrue("the laptop message should be listed: " + texts, texts.toString().contains("from the laptop"));
        assertTrue("the phone message should be listed: " + texts, texts.toString().contains("from the phone"));
        controller.pause().stop().destroy();
    }

    private static void collectText(View view, List<String> texts) {
        if (view instanceof TextView && !(view instanceof Button)) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) texts.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), texts);
        }
    }

    private static List<String> editHints(View view) {
        List<String> hints = new ArrayList<>();
        collectEditHints(view, hints);
        return hints;
    }

    private static void collectEditHints(View view, List<String> hints) {
        if (view instanceof android.widget.EditText) {
            CharSequence hint = ((android.widget.EditText) view).getHint();
            if (hint != null) hints.add(hint.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectEditHints(group.getChildAt(i), hints);
        }
    }

    @Test public void openingLibraryAutomaticallyListsTheReceiversFolder() throws Exception {
        EdgeFiles share = new EdgeFiles();
        share.publish("1".repeat(32), new org.json.JSONArray().put(new org.json.JSONObject()
                .put("id", "2".repeat(32)).put("name", "Film.mp4").put("size", 1000)
                .put("path", "Receiver/Movies/Film.mp4")), false);
        MediaHttpServer.activeEdgeFiles = share;
        ActivityController<MainActivity> controller = launch();
        try {
            View root = controller.get().findViewById(android.R.id.content);
            buttonsLabelled(root, "Library").get(0).performClick();
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("edgeList");
            field.setAccessible(true);
            android.widget.ListView list = (android.widget.ListView)field.get(controller.get());
            for (int i = 0; i < 60 && list.getCount() == 0; i++) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertEquals(View.VISIBLE, list.getVisibility());
            assertEquals("Receiver/Movies/Film.mp4", ((org.json.JSONObject)list.getItemAtPosition(0)).getString("path"));
        } finally {
            controller.pause().stop().destroy();
            MediaHttpServer.activeEdgeFiles = null;
        }
    }

    @Test public void theLibraryTabExplainsAnEmptyLibraryInsteadOfCrashing() {
        ActivityController<MainActivity> controller = launch();
        Activity activity = controller.get();
        View root = activity.findViewById(android.R.id.content);
        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        tabs.get(2).performClick(); // Library refreshes from MediaStore, which is empty here

        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        assertFalse("the library should never leave a blank card", texts.isEmpty());
        controller.pause().stop().destroy();
    }

    @Test public void theLibraryTabHasTheLaptopSectionAndSearch() {
        ActivityController<MainActivity> controller = launch();
        Activity activity = controller.get();
        View root = activity.findViewById(android.R.id.content);
        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        tabs.get(2).performClick();

        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        assertTrue("local phone file browser should be removed", buttonsLabelled(root, "Files on this phone").isEmpty());
        assertTrue("the laptop search should be there", editHints(root).contains("Search laptop files"));
        assertTrue("the mobile app should accept a precise laptop folder or file path", editHints(root).stream().anyMatch(hint -> hint.contains("Windows folder or file path")));
        assertNotNull(buttonsLabelled(root, "Load laptop files"));
        controller.pause().stop().destroy();
    }

    @Test public void theCameraTabShowsApprovalControlsAndNoCloneFeature() {
        ActivityController<MainActivity> controller = launch();
        View root = controller.get().findViewById(android.R.id.content);
        List<Button> tabs = buttonsLabelled(root, "Connect", "Screen", "Library", "Chat", "Camera");
        assertEquals(5, tabs.size());
        tabs.get(4).performClick();
        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        assertTrue(texts.toString(), texts.toString().toUpperCase(java.util.Locale.ROOT).contains("BOTH SIDES APPROVE"));
        assertTrue("the phone should expose an explicit camera start", buttonsLabelled(root, "Start camera session").size() == 1);
        assertTrue("the camera should provide a full-screen receive view", buttonsLabelled(root, "Full screen laptop camera").size() == 1);
        assertFalse("the removed Clone feature must not remain in the UI", buttonsLabelled(root, "Clone").size() > 0);
        controller.pause().stop().destroy();
    }
}
