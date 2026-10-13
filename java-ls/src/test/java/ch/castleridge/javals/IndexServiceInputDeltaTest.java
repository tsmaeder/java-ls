/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.ScanCollector;

class IndexServiceInputDeltaTest {

    @Test
    void applyInputDeltaAddsRemovesAndReplacesJars(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("com/example"));
        Files.writeString(src.resolve("com/example/App.java"),
                "package com.example;\npublic class App {}\n", StandardCharsets.UTF_8);

        Path jarA = tempDir.resolve("a.jar");
        Path jarB = tempDir.resolve("b.jar");
        zip(jarA, Map.of("com/lib/A.java", "package com.lib;\npublic class A {}\n".getBytes(StandardCharsets.UTF_8)));
        zip(jarB, Map.of("com/lib/B.java", "package com.lib;\npublic class B {}\n".getBytes(StandardCharsets.UTF_8)));

        ScanCollector collector = new ScanCollector();
        DirInput dir = new DirInput(src, collector);
        JarInput inputA = new JarInput(jarA, collector);
        JarInput inputB = new JarInput(jarB, collector);
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")), collector);

        IndexService service = new IndexService(null);
        List<IndexService.SourceRoot> roots = List.of(
                new IndexService.SourceRoot(src.toAbsolutePath().normalize(), dir.sourceUri()));
        service.index(List.of(dir, inputA, jrt), roots);

        Index index = service.index().orElseThrow();
        assertTrue(index.contains("com/example/App"));
        assertTrue(index.contains("com/lib/A"));
        assertFalse(index.contains("com/lib/B"));

        // Drop A, add B; dir and jrt unchanged.
        service.applyInputDelta(List.of(dir, inputB, jrt), roots);
        assertSame(index, service.index().orElseThrow());
        assertTrue(index.contains("com/example/App"));
        assertFalse(index.contains("com/lib/A"));
        assertTrue(index.contains("com/lib/B"));

        // Rewrite jar B with a different type and bump mtime.
        Thread.sleep(10);
        zip(jarB, Map.of("com/lib/B2.java",
                "package com.lib;\npublic class B2 {}\n".getBytes(StandardCharsets.UTF_8)));
        JarInput inputB2 = new JarInput(jarB, collector);
        service.applyInputDelta(List.of(dir, inputB2, jrt), roots);
        assertFalse(index.contains("com/lib/B"));
        assertTrue(index.contains("com/lib/B2"));
        assertTrue(index.contains("com/example/App"));
    }

    @Test
    void applyInputDeltaNoOpWhenInputsUnchanged(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Foo.java"), "public class Foo {}\n", StandardCharsets.UTF_8);

        ScanCollector collector = new ScanCollector();
        DirInput dir = new DirInput(src, collector);
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")), collector);
        IndexService service = new IndexService(null);
        List<IndexService.SourceRoot> roots = List.of(
                new IndexService.SourceRoot(src.toAbsolutePath().normalize(), dir.sourceUri()));
        service.index(List.of(dir, jrt), roots);
        Index index = service.index().orElseThrow();
        int before = index.entryCount();

        service.applyInputDelta(List.of(dir, jrt), roots);
        assertSame(index, service.index().orElseThrow());
        assertEquals(before, index.entryCount());
        assertTrue(index.contains("Foo"));
    }

    private static void zip(Path archive, Map<String, byte[]> entries) throws Exception {
        try (OutputStream out = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
