package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ResumeManifestTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String ranges(List<long[]> list) {
        StringBuilder text = new StringBuilder();
        for (long[] r : list) text.append(text.length() == 0 ? "" : ",").append(r[0]).append('-').append(r[1]);
        return text.toString();
    }

    @Test public void rangesMergeOutOfOrderOverlappingAndAdjacent() {
        ResumeManifest m = new ResumeManifest(100, "v", "");
        m.add(50, 60);
        m.add(10, 20);
        m.add(20, 30);          // adjacent: one range
        assertEquals("10-30,50-60", ranges(m.doneRanges()));
        m.add(25, 55);          // bridges both
        assertEquals("10-60", ranges(m.doneRanges()));
        m.add(90, 500);         // clamped to the size
        m.add(70, 70);          // empty: ignored
        m.add(-5, 2);
        assertEquals("0-2,10-60,90-100", ranges(m.doneRanges()));
        assertEquals(2 + 50 + 10, m.completedBytes());
        assertFalse(m.complete());
    }

    @Test public void missingRangesAreTheGaps() {
        ResumeManifest m = new ResumeManifest(100, "v", "");
        assertEquals("0-100", ranges(m.missing()));
        assertEquals(0, m.firstMissing());
        m.add(0, 40);
        m.add(60, 80);
        assertEquals("40-60,80-100", ranges(m.missing()));
        assertEquals(40, m.firstMissing());
        m.add(40, 60);
        m.add(80, 100);
        assertTrue(m.missing().isEmpty());
        assertTrue(m.complete());
        ResumeManifest late = new ResumeManifest(10, "v", "");
        late.add(3, 10);
        assertEquals(0, late.firstMissing());
        assertEquals("0-3", ranges(late.missing()));
    }

    @Test public void encodesAndDecodesBeyondFourGiB() {
        long size = 6L * 1024 * 1024 * 1024;
        ResumeManifest m = new ResumeManifest(size, "\"pb-1-abc\"", "http://x/file/a.mkv");
        m.add(0, 1L << 32);
        m.add(5L << 30, size);
        ResumeManifest back = ResumeManifest.decode(m.encode());
        assertNotNull(back);
        assertEquals(size, back.size);
        assertEquals("\"pb-1-abc\"", back.validator);
        assertEquals("http://x/file/a.mkv", back.source);
        assertEquals(ranges(m.doneRanges()), ranges(back.doneRanges()));
        assertNull(ResumeManifest.decode("garbage"));
        assertNull(ResumeManifest.decode(ResumeManifest.MAGIC + "\nsize=x\n"));
        assertNull(ResumeManifest.decode(ResumeManifest.MAGIC + "\nsize=10\ndone=a-b\n"));
        assertNull(ResumeManifest.decode(null));
    }

    @Test public void saveIsAtomicAndLeavesNoTempFile() throws Exception {
        File file = new File(temporary.getRoot(), "movie.pbresume");
        ResumeManifest first = new ResumeManifest(1000, "v1", "");
        first.add(0, 100);
        first.write(file);
        ResumeManifest second = new ResumeManifest(1000, "v1", "");
        second.add(0, 500);
        second.write(file);   // replaces the old one in place
        assertFalse(new File(temporary.getRoot(), "movie.pbresume.tmp").exists());
        assertEquals(500, ResumeManifest.read(file).completedBytes());
        // A crash mid-save leaves a stray temp file and the old manifest; the old one still reads.
        Files.write(new File(temporary.getRoot(), "movie.pbresume.tmp").toPath(), "PBRES".getBytes(StandardCharsets.UTF_8));
        assertEquals(500, ResumeManifest.read(file).completedBytes());
        ResumeManifest.writeText(file, second.encode());
        assertEquals(500, ResumeManifest.read(file).completedBytes());
        assertNull(ResumeManifest.read(new File(temporary.getRoot(), "absent")));
    }

    @Test public void aChangedSourceDoesNotMatch() {
        ResumeManifest m = new ResumeManifest(1000, "\"etag-1\"", "");
        assertTrue(m.matches(1000, "\"etag-1\""));
        assertFalse("new ETag", m.matches(1000, "\"etag-2\""));
        assertFalse("new size", m.matches(1001, "\"etag-1\""));
        assertFalse(m.matches(1000, null));
        assertFalse("no validator never matches", new ResumeManifest(1000, "", "").matches(1000, ""));
        assertEquals("1000:42", ResumeManifest.sizeMtime(1000, 42));
    }
}
