package com.pocketbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure maths for the transfer engine: how a file is split into byte ranges,
 * how big a chunk is, and how many connections carry it. No I/O here, so the
 * rules are unit tested on the JVM and shared by the downloader, the uploader
 * and (in JavaScript, with the same constants) the Edge page.
 */
final class TransferPlan {
    static final long MIB = 1024L * 1024L;
    /** Smallest and largest chunk a single ranged request carries. */
    static final long MIN_CHUNK = MIB;
    static final long MAX_CHUNK = 16 * MIB;
    /** Parallel connections for a large file: never fewer than 2, never more than 6. */
    static final int MIN_CONNECTIONS = 2;
    static final int MAX_CONNECTIONS = 6;
    /** Below this a file goes over one connection: setup would cost more than it saves. */
    static final long PARALLEL_THRESHOLD = 8 * MIB;
    /** At or below this, many files are sent together in one batch stream. */
    static final long SMALL_FILE = 2 * MIB;

    private TransferPlan() { }

    /** Connections to start with; the controller then moves between 2 and 6 by measured throughput. */
    static int initialConnections(long total) {
        if (total < PARALLEL_THRESHOLD) return 1;
        if (total < 256 * MIB) return MIN_CONNECTIONS;
        return 4;
    }

    /**
     * First chunk size for a file: about 1/8 of each connection's share, so a
     * file still spreads over every connection, clamped to 1..16 MiB and
     * rounded down to whole MiB.
     */
    static long initialChunk(long total, int connections) {
        if (total <= 0) return MIN_CHUNK;
        long share = total / Math.max(1, connections) / 8;
        return clampChunk(share);
    }

    static long clampChunk(long size) {
        long clamped = Math.max(MIN_CHUNK, Math.min(MAX_CHUNK, size));
        return clamped / MIB * MIB;
    }

    /**
     * Next chunk size from how long the last one took: under half a second
     * doubles it, over two seconds halves it, otherwise it stays. A fast link
     * reaches 16 MiB chunks (fewer requests); a slow or contended one drops
     * towards 1 MiB so a lost connection costs little and progress stays smooth.
     */
    static long adaptChunk(long current, long bytes, long nanos) {
        if (bytes <= 0 || nanos <= 0) return clampChunk(current);
        double seconds = nanos / 1e9;
        if (seconds < 0.5) return clampChunk(current * 2);
        if (seconds > 2.0) return clampChunk(current / 2);
        return clampChunk(current);
    }

    /**
     * Splits [start, endExclusive) into consecutive ranges of at most
     * {@code chunk} bytes. Every byte is covered exactly once.
     */
    static List<long[]> split(long start, long endExclusive, long chunk) {
        if (chunk <= 0) throw new IllegalArgumentException("chunk must be positive");
        List<long[]> ranges = new ArrayList<>();
        for (long at = start; at < endExclusive; at += chunk) {
            ranges.add(new long[]{at, Math.min(endExclusive, at + chunk)});
        }
        return ranges;
    }

    /** "bytes=a-b" for the inclusive HTTP form of [start, endExclusive). */
    static String rangeHeader(long start, long endExclusive) {
        return "bytes=" + start + "-" + (endExclusive - 1);
    }

    /**
     * Total size from a Content-Range header ("bytes 0-99/1234"), or -1 when
     * the header is missing or the size is unknown ("*").
     */
    static long totalFromContentRange(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        try { return Long.parseLong(header.substring(slash + 1).trim()); }
        catch (NumberFormatException unknown) { return -1; }
    }

    /** Start offset from a Content-Range header, or -1. */
    static long startFromContentRange(String header) {
        if (header == null) return -1;
        String value = header.trim();
        if (value.startsWith("bytes ")) value = value.substring(6);
        int dash = value.indexOf('-');
        if (dash <= 0) return -1;
        try { return Long.parseLong(value.substring(0, dash).trim()); }
        catch (NumberFormatException invalid) { return -1; }
    }

    /**
     * Decides the number of parallel connections from measured throughput.
     * It probes upwards one connection at a time while each step still adds
     * at least 8% throughput, steps back when a step lost more than 8%, and
     * then holds. Hotspot links usually top out at 3-4 connections; adding
     * more only adds contention.
     */
    static final class Concurrency {
        private final int min;
        private final int max;
        private int current;
        private double lastRate = -1;
        private boolean settled;

        Concurrency(int initial, int min, int max) {
            this.min = Math.max(1, min);
            this.max = Math.max(this.min, max);
            this.current = Math.max(this.min, Math.min(this.max, initial));
        }

        int current() { return current; }

        /** Feeds one measurement window (bytes per second at the current count); returns the new count. */
        int observe(double bytesPerSecond) {
            if (bytesPerSecond <= 0) return current;
            if (lastRate < 0) {
                lastRate = bytesPerSecond;
                if (current < max) current++;
                else settled = true;
                return current;
            }
            if (settled) {
                // A big drop (another app, a weaker signal) re-opens the search downwards.
                if (bytesPerSecond < lastRate * 0.6 && current > min) { current--; settled = false; }
                lastRate = bytesPerSecond;
                return current;
            }
            if (bytesPerSecond >= lastRate * 1.08) {
                lastRate = bytesPerSecond;
                if (current < max) current++;
                else settled = true;
            } else if (bytesPerSecond < lastRate * 0.92) {
                if (current > min) current--;
                settled = true;
            } else {
                settled = true;
                lastRate = Math.max(lastRate, bytesPerSecond);
            }
            return current;
        }
    }
}
