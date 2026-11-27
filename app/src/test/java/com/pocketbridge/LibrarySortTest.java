package com.pocketbridge;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The six Library orders, natural name order, categories and stable paging. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LibrarySortTest {

    private static MediaRepository.Item item(String key, String name, long size, long modified) {
        return new MediaRepository.Item(key, name, null, "file", size, modified, Uri.parse("content://media/" + key));
    }

    private static List<String> names(List<MediaRepository.Item> items) {
        List<String> out = new ArrayList<>();
        for (MediaRepository.Item i : items) out.add(i.name);
        return out;
    }

    @Test public void parsesEveryOrderAndFallsBackToNewest() {
        for (LibrarySort sort : LibrarySort.values()) assertEquals(sort, LibrarySort.parse(sort.id));
        assertEquals(LibrarySort.NAME_DESC, LibrarySort.parse(" NAME_DESC "));
        assertEquals(LibrarySort.DATE_DESC, LibrarySort.parse(null));
        assertEquals(LibrarySort.DATE_DESC, LibrarySort.parse("random"));
    }

    @Test public void naturalOrderIsNumericAndCaseInsensitive() {
        LibrarySort.NaturalOrder order = LibrarySort.NaturalOrder.INSTANCE;
        List<String> names = new ArrayList<>(Arrays.asList("episode 10.mkv", "Episode 2.mkv", "episode 1.mkv",
                "IMG_100.jpg", "img_20.jpg", "Zebra.png", "apple.png"));
        Collections.shuffle(names, new java.util.Random(4));
        names.sort(order);
        assertEquals(Arrays.asList("apple.png", "episode 1.mkv", "Episode 2.mkv", "episode 10.mkv",
                "img_20.jpg", "IMG_100.jpg", "Zebra.png"), names);
        assertTrue(order.compare("a", "A") != 0);
        assertTrue("leading zeros still give a total order", order.compare("7 file", "007 file") != 0);
        assertTrue(order.compare("2 b", "10 a") < 0);
        assertTrue(order.compare("file99999999999999999999", "file100000000000000000000") < 0);
        assertEquals(0, order.compare("same", "same"));
    }

    @Test public void everyOrderSortsAsNamed() {
        List<MediaRepository.Item> items = Arrays.asList(
                item("file:1", "Episode 10.mkv", 300, 10),
                item("file:2", "episode 2.mkv", 100, 30),
                item("file:3", "Trailer.mp4", 200, 20));
        assertEquals(Arrays.asList("episode 2.mkv", "Episode 10.mkv", "Trailer.mp4"), names(MediaRepository.sorted(items, LibrarySort.NAME_ASC)));
        assertEquals(Arrays.asList("Trailer.mp4", "Episode 10.mkv", "episode 2.mkv"), names(MediaRepository.sorted(items, LibrarySort.NAME_DESC)));
        assertEquals(Arrays.asList("episode 2.mkv", "Trailer.mp4", "Episode 10.mkv"), names(MediaRepository.sorted(items, LibrarySort.DATE_DESC)));
        assertEquals(Arrays.asList("Episode 10.mkv", "Trailer.mp4", "episode 2.mkv"), names(MediaRepository.sorted(items, LibrarySort.DATE_ASC)));
        assertEquals(Arrays.asList("Episode 10.mkv", "Trailer.mp4", "episode 2.mkv"), names(MediaRepository.sorted(items, LibrarySort.SIZE_DESC)));
        assertEquals(Arrays.asList("episode 2.mkv", "Trailer.mp4", "Episode 10.mkv"), names(MediaRepository.sorted(items, LibrarySort.SIZE_ASC)));
    }

    @Test public void pagingIsStableWithTiesUnderEverySort() {
        List<MediaRepository.Item> items = new ArrayList<>();
        // Many equal sizes, dates and names: only the key separates them.
        for (int i = 0; i < 257; i++) items.add(item("video:" + i, "clip " + (i % 5) + ".mp4", 1000 + (i % 3), 50 + (i % 4)));
        for (LibrarySort sort : LibrarySort.values()) {
            List<MediaRepository.Item> shuffled = new ArrayList<>(items);
            Collections.shuffle(shuffled, new java.util.Random(sort.ordinal()));
            List<MediaRepository.Item> once = MediaRepository.sorted(items, sort);
            List<MediaRepository.Item> again = MediaRepository.sorted(shuffled, sort);
            List<String> paged = new ArrayList<>();
            int offset = 0;
            while (true) {
                MediaRepository.Page page = MediaRepository.slice(again, offset, 40);
                for (MediaRepository.Item i : page.items) paged.add(i.key);
                offset = page.nextOffset;
                if (!page.hasMore) break;
            }
            List<String> expected = new ArrayList<>();
            for (MediaRepository.Item i : once) expected.add(i.key);
            assertEquals(sort.id, expected, paged);
            assertEquals(sort.id, 257, new java.util.HashSet<>(paged).size());
        }
    }

    @Test public void categoriesSplitDocumentsAppsAndOther() {
        assertEquals("documents", MediaRepository.normalizeKind("documents"));
        assertEquals("apps", MediaRepository.normalizeKind("apps"));
        assertEquals("other", MediaRepository.normalizeKind("other"));
        assertEquals("audio", MediaRepository.normalizeKind("music"));
        assertTrue(MediaRepository.matches("documents", "file", "application/pdf", "a.pdf"));
        assertTrue(MediaRepository.matches("documents", "file", "", "notes.TXT"));
        assertTrue(MediaRepository.matches("apps", "file", "application/vnd.android.package-archive", "game.apk"));
        assertTrue(MediaRepository.matches("apps", "file", "", "setup.exe"));
        assertTrue(MediaRepository.matches("other", "file", "application/zip", "backup.zip"));
        assertFalse(MediaRepository.matches("other", "file", "application/pdf", "a.pdf"));
        assertTrue(MediaRepository.matches("files", "file", "application/zip", "backup.zip"));
        assertTrue(MediaRepository.matches("files", "file", "application/pdf", "a.pdf"));
        assertFalse(MediaRepository.matches("files", "video", "video/mp4", "a.mp4"));
        assertTrue(MediaRepository.matches("videos", "file", "application/octet-stream", "movie.MKV"));
        assertTrue(MediaRepository.matches("all", "file", "", "anything"));
    }

    @Test public void laptopRowsFilterAndSortTheSameWay() throws Exception {
        List<org.json.JSONObject> rows = new ArrayList<>();
        rows.add(new org.json.JSONObject().put("name", "Ep 10.mp4").put("path", "b/Ep 10.mp4").put("size", 5).put("modified", 1));
        rows.add(new org.json.JSONObject().put("name", "ep 9.mp4").put("path", "a/ep 9.mp4").put("size", 9).put("modified", 2));
        rows.add(new org.json.JSONObject().put("name", "manual.pdf").put("path", "manual.pdf").put("size", 1).put("modified", 3));
        rows.add(new org.json.JSONObject().put("name", "app.apk").put("path", "app.apk").put("size", 2).put("modified", 4));
        List<org.json.JSONObject> videos = MainActivity.filterAndSortRows(rows, "", "videos", LibrarySort.NAME_ASC);
        assertEquals(2, videos.size());
        assertEquals("ep 9.mp4", videos.get(0).getString("name"));
        assertEquals("Ep 10.mp4", MainActivity.filterAndSortRows(rows, "", "videos", LibrarySort.SIZE_ASC).get(0).getString("name"));
        assertEquals("manual.pdf", MainActivity.filterAndSortRows(rows, "", "documents", LibrarySort.DATE_DESC).get(0).getString("name"));
        assertEquals("app.apk", MainActivity.filterAndSortRows(rows, "", "apps", LibrarySort.DATE_DESC).get(0).getString("name"));
        assertEquals("app.apk", MainActivity.filterAndSortRows(rows, "", "all", LibrarySort.DATE_DESC).get(0).getString("name"));
        assertEquals(1, MainActivity.filterAndSortRows(rows, "MANUAL", "all", LibrarySort.NAME_ASC).size());
    }
}
