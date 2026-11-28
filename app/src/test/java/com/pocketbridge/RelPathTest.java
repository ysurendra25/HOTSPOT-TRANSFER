package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RelPathTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void traversalAndOddSegmentsAreRemoved() {
        assertEquals("Holiday/day 1/IMG_001.jpg", RelPath.sanitize("Holiday/day 1/IMG_001.jpg"));
        assertEquals("etc/passwd", RelPath.sanitize("../../etc/passwd"));
        assertEquals("a/b", RelPath.sanitize("/a//./b/"));
        assertEquals("C_/Users/x.txt", RelPath.sanitize("C:\\Users\\x.txt"));
        assertEquals("a_b_.txt", RelPath.sanitize("a*b?.txt"));
        assertEquals("name", RelPath.sanitize("name. . "));
        assertEquals("_CON/_nul.txt", RelPath.sanitize("CON/nul.txt"));
        assertEquals("console", RelPath.sanitize("console"));
        assertNull(RelPath.sanitize(".."));
        assertNull(RelPath.sanitize("/./"));
        assertNull(RelPath.sanitize(null));
    }

    @Test public void depthAndLengthAreBounded() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < RelPath.MAX_DEPTH; i++) deep.append("d/");
        assertEquals(RelPath.MAX_DEPTH, RelPath.sanitize(deep.toString()).split("/").length);
        assertNull(RelPath.sanitize(deep + "one-too-many"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append('x');
        assertEquals(RelPath.MAX_SEGMENT, RelPath.sanitize(longName.toString()).length());
    }

    @Test public void resolveStaysInsideTheRootAndMakesFolders() throws Exception {
        File root = temporary.newFolder("received");
        File target = RelPath.resolve(root, "../outside/../x/y.txt");
        assertTrue(target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
        assertTrue(target.getParentFile().isDirectory());
        assertEquals("y.txt", target.getName());
        try { RelPath.resolve(root, "../.."); fail("an empty path must be refused"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Invalid")); }
    }

    @Test public void nameClashesGetANumber() throws Exception {
        File root = temporary.newFolder("r");
        File first = new File(root, "photo.jpg");
        assertEquals(first, RelPath.unique(first));
        assertTrue(first.createNewFile());
        File second = RelPath.unique(first);
        assertEquals("photo (2).jpg", second.getName());
        assertTrue(second.createNewFile());
        assertEquals("photo (3).jpg", RelPath.unique(first).getName());
        File plain = new File(root, "README");
        assertTrue(plain.createNewFile());
        assertEquals("README (2)", RelPath.unique(plain).getName());
        File hidden = new File(root, ".env");
        assertTrue(hidden.createNewFile());
        assertEquals(".env (2)", RelPath.unique(hidden).getName());
    }
}
