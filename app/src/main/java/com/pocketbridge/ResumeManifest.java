package com.pocketbridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What part of a transfer is safely on disk, so an interrupted download or
 * upload continues from the completed ranges instead of starting over.
 *
 * Format (UTF-8 text, one key per line, written to a temp file and renamed):
 * <pre>
 * PBRESUME 1
 * size=4294967296
 * validator="pb-1000-18c2..."       the source's ETag, or size:mtime when it has none
 * source=http://.../file/a.mkv      informational
 * done=0-16777216,33554432-50331648 half-open [start,end) ranges, sorted, merged
 * </pre>
 * A manifest is only written after the data it describes has been forced to
 * disk, so a crash can lose progress but never marks unwritten bytes as done.
 */
final class ResumeManifest {
    static final String MAGIC = "PBRESUME 1";

    final long size;
    final String validator;
    final String source;
    private final List<long[]> done = new ArrayList<>();

    ResumeManifest(long size, String validator, String source) {
        this.size = size;
        this.validator = validator == null ? "" : validator;
        this.source = source == null ? "" : source;
    }

    /** True when the source still matches: same size and same validator. */
    boolean matches(long otherSize, String otherValidator) {
        return size == otherSize && !validator.isEmpty() && validator.equals(otherValidator == null ? "" : otherValidator);
    }

    /** Marks [start, end) complete, merging with neighbours. */
    synchronized void add(long start, long end) {
        if (end <= start) return;
        start = Math.max(0, start);
        end = Math.min(size, end);
        if (end <= start) return;
        List<long[]> merged = new ArrayList<>();
        boolean placed = false;
        for (long[] range : done) {
            if (range[1] < start) { merged.add(range); continue; }
            if (range[0] > end) {
                if (!placed) { merged.add(new long[]{start, end}); placed = true; }
                merged.add(range);
                continue;
            }
            start = Math.min(start, range[0]);
            end = Math.max(end, range[1]);
        }
        if (!placed) merged.add(new long[]{start, end});
        merged.sort((a, b) -> Long.compare(a[0], b[0]));
        done.clear();
        done.addAll(merged);
    }

    synchronized long completedBytes() {
        long total = 0;
        for (long[] range : done) total += range[1] - range[0];
        return total;
    }

    synchronized boolean complete() { return completedBytes() >= size; }

    /** Completed ranges, copied. */
    synchronized List<long[]> doneRanges() {
        List<long[]> copy = new ArrayList<>();
        for (long[] range : done) copy.add(new long[]{range[0], range[1]});
        return copy;
    }

    /** The gaps still to transfer, in order. */
    synchronized List<long[]> missing() {
        List<long[]> gaps = new ArrayList<>();
        long at = 0;
        for (long[] range : done) {
            if (range[0] > at) gaps.add(new long[]{at, range[0]});
            at = Math.max(at, range[1]);
        }
        if (at < size) gaps.add(new long[]{at, size});
        return gaps;
    }

    /** First byte not yet received (the resume offset for a sequential sender). */
    synchronized long firstMissing() {
        return done.isEmpty() || done.get(0)[0] > 0 ? 0 : done.get(0)[1];
    }

    synchronized String encode() {
        StringBuilder text = new StringBuilder(MAGIC).append('\n');
        text.append("size=").append(size).append('\n');
        text.append("validator=").append(validator.replace('\n', ' ')).append('\n');
        text.append("source=").append(source.replace('\n', ' ')).append('\n');
        text.append("done=");
        for (int i = 0; i < done.size(); i++) {
            if (i > 0) text.append(',');
            text.append(done.get(i)[0]).append('-').append(done.get(i)[1]);
        }
        return text.append('\n').toString();
    }

    /** Parses a manifest; null when the text is not a valid one. */
    static ResumeManifest decode(String text) {
        if (text == null || !text.startsWith(MAGIC)) return null;
        long size = -1;
        String validator = "", source = "", ranges = "";
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq), value = line.substring(eq + 1);
            switch (key) {
                case "size": try { size = Long.parseLong(value.trim()); } catch (NumberFormatException bad) { return null; } break;
                case "validator": validator = value; break;
                case "source": source = value; break;
                case "done": ranges = value.trim(); break;
                default: break;
            }
        }
        if (size < 0) return null;
        ResumeManifest manifest = new ResumeManifest(size, validator, source);
        if (!ranges.isEmpty()) {
            for (String part : ranges.split(",")) {
                int dash = part.indexOf('-');
                if (dash <= 0) return null;
                try { manifest.add(Long.parseLong(part.substring(0, dash)), Long.parseLong(part.substring(dash + 1))); }
                catch (NumberFormatException bad) { return null; }
            }
        }
        return manifest;
    }

    static ResumeManifest read(File file) {
        if (file == null || !file.isFile() || file.length() > 1024 * 1024) return null;
        try { return decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException unreadable) { return null; }
    }

    /** Writes atomically: temp file, fsync, rename. */
    void write(File file) throws IOException {
        writeText(file, encode());
    }

    /** Writes an encoded manifest (taken before the data was forced) atomically. */
    static void writeText(File file, String text) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) throw new IOException("Could not save transfer progress.");
        }
    }

    /** Validator for a local file or a source without an ETag. */
    static String sizeMtime(long size, long modifiedMillis) {
        return size + ":" + modifiedMillis;
    }
}
