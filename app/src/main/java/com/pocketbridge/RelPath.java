package com.pocketbridge;

import java.io.File;
import java.io.IOException;

/**
 * Relative paths for folder transfers ("Holiday/day 1/IMG_001.jpg"). A path
 * from the network is reduced to safe segments: no "..", no ".", no empty
 * segments, no drive letters or reserved characters, at most 32 levels. The
 * result can only ever point inside the receiving folder.
 */
final class RelPath {
    static final int MAX_DEPTH = 32;
    static final int MAX_SEGMENT = 200;
    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    private RelPath() { }

    /** Safe "a/b/c" form of {@code raw}, or null when nothing usable is left. */
    static String sanitize(String raw) {
        if (raw == null) return null;
        String[] parts = raw.replace('\\', '/').split("/");
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (String part : parts) {
            String segment = part.replaceAll("[:*?\"<>|\\p{Cntrl}]", "_").trim();
            // Windows drops trailing dots and spaces; "a." and "a" would collide.
            while (segment.endsWith(".") || segment.endsWith(" ")) segment = segment.substring(0, segment.length() - 1);
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) continue;
            if (segment.length() > MAX_SEGMENT) segment = segment.substring(0, MAX_SEGMENT).trim();
            // CON, NUL, COM1... cannot be created on Windows when the folder is copied there later.
            if (RESERVED.matcher(segment).matches()) segment = "_" + segment;
            if (++depth > MAX_DEPTH) return null;
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** {@code root}/{@code rel}, checked to stay inside root; parent folders are created. */
    static File resolve(File root, String rel) throws IOException {
        String clean = sanitize(rel);
        if (clean == null) throw new IOException("Invalid file path.");
        File target = new File(root, clean.replace('/', File.separatorChar));
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootPath)) throw new IOException("Invalid file path.");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create folder " + parent.getName() + ".");
        return target;
    }

    /** A sibling name that does not exist yet: "a.txt", "a (2).txt", ... */
    static File unique(File candidate) {
        if (!candidate.exists()) return candidate;
        String name = candidate.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int counter = 2; ; counter++) {
            File next = new File(candidate.getParentFile(), base + " (" + counter + ")" + extension);
            if (!next.exists()) return next;
        }
    }
}
