package com.pocketbridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which buttons a shared chat file gets. The phone app and the Edge page use the
 * same rules (the page mirrors them in chatActionsFor):
 * video: Play, Play in VLC, Download; music: Play, Download; anything else: Open, Download.
 */
final class ChatActions {
    enum Action { PLAY, PLAY_IN_VLC, OPEN, DOWNLOAD }

    static final String VLC_PACKAGE = "org.videolan.vlc";

    private ChatActions() { }

    /** video, audio or file, from the MIME type first and the file name second. */
    static String kind(String mime, String name) {
        String kind = MediaTypeClassifier.kind(mime, name, "file");
        return "video".equals(kind) || "audio".equals(kind) ? kind : "file";
    }

    static List<Action> forKind(String kind) {
        if ("video".equals(kind)) return Collections.unmodifiableList(Arrays.asList(Action.PLAY, Action.PLAY_IN_VLC, Action.DOWNLOAD));
        if ("audio".equals(kind)) return Collections.unmodifiableList(Arrays.asList(Action.PLAY, Action.DOWNLOAD));
        return Collections.unmodifiableList(Arrays.asList(Action.OPEN, Action.DOWNLOAD));
    }

    static List<Action> forFile(String mime, String name) {
        return forKind(kind(mime, name));
    }

    static String label(Action action) {
        switch (action) {
            case PLAY: return "Play";
            case PLAY_IN_VLC: return "Play in VLC";
            case OPEN: return "Open";
            default: return "Download";
        }
    }

    /** The MIME type to hand a player: the reported one when specific, else from the name. */
    static String playerMime(String mime, String name) {
        String resolved = MediaTypeClassifier.mimeType(name, mime);
        return resolved == null || resolved.isEmpty() ? "*/*" : resolved;
    }
}
