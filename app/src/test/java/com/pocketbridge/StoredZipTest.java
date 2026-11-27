package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/** The /api/zip archive: its Content-Length is planned exactly and the result opens. */
public class StoredZipTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void plannedLengthMatchesTheBytesWrittenAndTheZipOpens() throws Exception {
        List<String> names = Arrays.asList("a.txt", "café.jpg", "empty-ish.bin");
        byte[][] bodies = {"hello".getBytes(StandardCharsets.UTF_8), new byte[70_000], new byte[]{1}};
        for (int i = 0; i < bodies[1].length; i++) bodies[1][i] = (byte) (i * 31);
        List<Long> sizes = Arrays.asList(5L, 70_000L, 1L);
        long planned = StoredZip.plannedLength(names, sizes);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StoredZip.Writer zip = new StoredZip.Writer(out, 1_700_000_000_000L);
        for (int i = 0; i < names.size(); i++) {
            OutputStream entry = zip.begin(names.get(i), sizes.get(i));
            entry.write(bodies[i]);
            zip.end();
        }
        zip.finish();
        assertEquals(planned, out.size());

        File file = temp.newFile("out.zip");
        Files.write(file.toPath(), out.toByteArray());
        try (ZipFile reader = new ZipFile(file, StandardCharsets.UTF_8)) {
            assertEquals(3, reader.size());
            for (int i = 0; i < names.size(); i++) {
                ZipEntry entry = reader.getEntry(names.get(i));
                assertNotNull(names.get(i), entry);
                assertEquals(ZipEntry.STORED, entry.getMethod());
                try (InputStream input = reader.getInputStream(entry)) {
                    assertArrayEquals(bodies[i], input.readAllBytes());
                }
            }
        }
    }

    @Test public void aFileThatShrinksOrGrowsStopsTheArchive() throws Exception {
        StoredZip.Writer zip = new StoredZip.Writer(new ByteArrayOutputStream(), 0);
        zip.begin("short.bin", 10).write(new byte[4]);
        try { zip.end(); fail("short entry accepted"); } catch (IOException expected) { }

        StoredZip.Writer other = new StoredZip.Writer(new ByteArrayOutputStream(), 0);
        OutputStream entry = other.begin("long.bin", 2);
        try { entry.write(new byte[3]); fail("long entry accepted"); } catch (IOException expected) { }
    }

    @Test public void sizesThatNeedZip64AreNotPlanned() {
        assertEquals(-1, StoredZip.plannedLength(Collections.singletonList("big"), Collections.singletonList(0x1_0000_0000L)));
        assertEquals(-1, StoredZip.plannedLength(Arrays.asList("a", "b"), Arrays.asList(0xF000_0000L, 0xF000_0000L)));
        assertEquals(-1, StoredZip.plannedLength(Collections.singletonList("a"), Collections.singletonList(-1L)));
        assertEquals(22, StoredZip.plannedLength(Collections.emptyList(), Collections.emptyList()));
    }
}
