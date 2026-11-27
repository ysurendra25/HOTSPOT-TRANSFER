package com.pocketbridge;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * The six Library orders shared by the phone server (/api/files), the phone's
 * laptop-file list and the Edge page. Every order ends in a unique key, so a
 * list sorted twice comes out the same and paging never repeats or skips.
 */
enum LibrarySort {
    NAME_ASC("name_asc"), NAME_DESC("name_desc"), DATE_DESC("date_desc"),
    DATE_ASC("date_asc"), SIZE_DESC("size_desc"), SIZE_ASC("size_asc");

    static final LibrarySort DEFAULT = DATE_DESC;
    final String id;

    LibrarySort(String id) { this.id = id; }

    /** Unknown or missing values fall back to newest first, the old order. */
    static LibrarySort parse(String value) {
        if (value == null) return DEFAULT;
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (LibrarySort sort : values()) if (sort.id.equals(wanted)) return sort;
        return DEFAULT;
    }

    /** Reads the fields a sort needs from any row type (MediaStore items, laptop JSON rows). */
    interface Fields<T> {
        String name(T row);
        long modified(T row);
        long size(T row);
        String key(T row);
    }

    <T> Comparator<T> comparator(Fields<T> f) {
        Comparator<T> byName = (a, b) -> NaturalOrder.INSTANCE.compare(f.name(a), f.name(b));
        Comparator<T> byKey = (a, b) -> safe(f.key(a)).compareTo(safe(f.key(b)));
        switch (this) {
            case NAME_ASC: return byName.thenComparing(byKey);
            case NAME_DESC: return byName.reversed().thenComparing(byKey);
            case DATE_ASC: return Comparator.<T>comparingLong(f::modified).thenComparing(byName).thenComparing(byKey);
            case SIZE_DESC: return Comparator.<T>comparingLong(f::size).reversed().thenComparing(byName).thenComparing(byKey);
            case SIZE_ASC: return Comparator.<T>comparingLong(f::size).thenComparing(byName).thenComparing(byKey);
            case DATE_DESC:
            default: return Comparator.<T>comparingLong(f::modified).reversed().thenComparing(byKey.reversed());
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    /**
     * Natural, case-insensitive name order: "Episode 2" before "episode 10",
     * "IMG_9" before "img_10". Text runs use the locale collator at secondary
     * strength (accents count, case does not); digit runs compare as numbers of
     * any length. Equal names fall back to a plain comparison so the order is total.
     */
    static final class NaturalOrder implements Comparator<String> {
        static final NaturalOrder INSTANCE = new NaturalOrder();
        private final Collator collator;

        NaturalOrder() {
            collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.SECONDARY);
        }

        @Override public int compare(String left, String right) {
            String a = left == null ? "" : left;
            String b = right == null ? "" : right;
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                boolean digitA = Character.isDigit(a.charAt(i));
                boolean digitB = Character.isDigit(b.charAt(j));
                int endA = run(a, i, digitA);
                int endB = run(b, j, digitB);
                int result;
                if (digitA && digitB) {
                    result = compareNumbers(a.substring(i, endA), b.substring(j, endB));
                } else if (digitA != digitB) {
                    // Numbers sort before letters, as in most file managers.
                    result = digitA ? -1 : 1;
                } else {
                    synchronized (collator) { result = collator.compare(a.substring(i, endA), b.substring(j, endB)); }
                }
                if (result != 0) return result;
                i = endA;
                j = endB;
            }
            int remaining = Integer.compare(a.length() - i, b.length() - j);
            if (remaining != 0) return remaining;
            // Same name apart from case or leading zeros: keep a stable, total order.
            synchronized (collator) {
                int tertiary = collator.getStrength();
                collator.setStrength(Collator.TERTIARY);
                int byCase = collator.compare(a, b);
                collator.setStrength(tertiary);
                if (byCase != 0) return byCase;
            }
            return a.compareTo(b);
        }

        private static int run(String text, int start, boolean digits) {
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end)) == digits) end++;
            return end;
        }

        private static int compareNumbers(String a, String b) {
            String x = stripZeros(a), y = stripZeros(b);
            if (x.length() != y.length()) return Integer.compare(x.length(), y.length());
            for (int k = 0; k < x.length(); k++) {
                int d = Character.digit(x.charAt(k), 10) - Character.digit(y.charAt(k), 10);
                if (d != 0) return d;
            }
            // 7 before 007 only after the whole name ties; here they count as equal.
            return 0;
        }

        private static String stripZeros(String digits) {
            int k = 0;
            while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
            return digits.substring(k);
        }
    }
}
