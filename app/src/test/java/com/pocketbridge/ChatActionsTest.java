package com.pocketbridge;

import org.junit.Test;

import java.util.Arrays;

import static com.pocketbridge.ChatActions.Action.DOWNLOAD;
import static com.pocketbridge.ChatActions.Action.OPEN;
import static com.pocketbridge.ChatActions.Action.PLAY;
import static com.pocketbridge.ChatActions.Action.PLAY_IN_VLC;
import static org.junit.Assert.assertEquals;

/** Which buttons each kind of chat file gets, on the phone and (mirrored) on the Edge page. */
public class ChatActionsTest {

    @Test public void videoGetsPlayVlcAndDownload() {
        assertEquals(Arrays.asList(PLAY, PLAY_IN_VLC, DOWNLOAD), ChatActions.forFile("video/mp4", "clip.mp4"));
        // Generic MIME: the extension decides.
        assertEquals(Arrays.asList(PLAY, PLAY_IN_VLC, DOWNLOAD), ChatActions.forFile("application/octet-stream", "movie.MKV"));
        assertEquals(Arrays.asList(PLAY, PLAY_IN_VLC, DOWNLOAD), ChatActions.forFile("", "rec.ts"));
    }

    @Test public void musicGetsPlayAndDownload() {
        assertEquals(Arrays.asList(PLAY, DOWNLOAD), ChatActions.forFile("audio/mpeg", "song.mp3"));
        assertEquals(Arrays.asList(PLAY, DOWNLOAD), ChatActions.forFile(null, "track.flac"));
    }

    @Test public void everythingElseGetsOpenAndDownload() {
        assertEquals(Arrays.asList(OPEN, DOWNLOAD), ChatActions.forFile("application/pdf", "doc.pdf"));
        assertEquals(Arrays.asList(OPEN, DOWNLOAD), ChatActions.forFile("image/jpeg", "photo.jpg"));
        assertEquals(Arrays.asList(OPEN, DOWNLOAD), ChatActions.forFile(null, "notes"));
        assertEquals(Arrays.asList(OPEN, DOWNLOAD), ChatActions.forFile("application/zip", "files.zip"));
    }

    @Test public void kindsAndLabels() {
        assertEquals("video", ChatActions.kind("video/webm", "x"));
        assertEquals("audio", ChatActions.kind("audio/ogg", "x"));
        assertEquals("file", ChatActions.kind("image/png", "x.png"));
        assertEquals("Play in VLC", ChatActions.label(PLAY_IN_VLC));
        assertEquals("Open", ChatActions.label(OPEN));
        assertEquals("Download", ChatActions.label(DOWNLOAD));
        assertEquals("org.videolan.vlc", ChatActions.VLC_PACKAGE);
    }

    @Test public void playerMimeComesFromTheNameWhenGeneric() {
        assertEquals("video/x-matroska", ChatActions.playerMime("application/octet-stream", "a.mkv"));
        assertEquals("video/mp4", ChatActions.playerMime("video/mp4", "a.bin"));
        assertEquals("application/octet-stream", ChatActions.playerMime("", "a.unknownext"));
    }
}
