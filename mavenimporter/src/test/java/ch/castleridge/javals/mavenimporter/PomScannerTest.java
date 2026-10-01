/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PomScannerTest {

    @TempDir
    Path temp;

    @Test
    void ordersByPathSegmentCount() throws Exception {
        Path deep = temp.resolve("a/b/c");
        Files.createDirectories(deep);
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Files.writeString(temp.resolve("a/pom.xml"), "<project/>");
        Files.createDirectories(temp.resolve("a/b"));
        Files.writeString(temp.resolve("a/b/pom.xml"), "<project/>");
        Files.writeString(deep.resolve("pom.xml"), "<project/>");

        Set<Path> found = PomScanner.scan(temp);
        List<Path> ordered = new ArrayList<>(found);
        assertEquals(4, ordered.size());
        assertTrue(PomScanner.pathSegmentCount(ordered.get(0))
                <= PomScanner.pathSegmentCount(ordered.get(1)));
        assertEquals(temp.resolve("pom.xml").toAbsolutePath().normalize(), ordered.get(0));
    }

    @Test
    void skipsTargetDirectories() throws Exception {
        Path target = temp.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("pom.xml"), "<project/>");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");

        Set<Path> found = PomScanner.scan(temp);
        assertEquals(1, found.size());
    }

    @Test
    void skipsSrcDirectories() throws Exception {
        Path underSrc = temp.resolve("src/test/resources/fixtures/mod");
        Files.createDirectories(underSrc);
        Files.writeString(underSrc.resolve("pom.xml"), "<project/>");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");

        Set<Path> found = PomScanner.scan(temp);
        assertEquals(1, found.size());
        assertEquals(temp.resolve("pom.xml").toAbsolutePath().normalize(), found.iterator().next());
    }

    @Test
    void takeShortestPrefersShallower() {
        Set<Path> todo = new LinkedHashSet<>();
        todo.add(Path.of("/ws/a/b/pom.xml"));
        todo.add(Path.of("/ws/pom.xml"));
        todo.add(Path.of("/ws/a/pom.xml"));
        assertEquals(Path.of("/ws/pom.xml"), PomScanner.takeShortest(todo));
    }
}
