/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.FileIndexer;
import ch.castleridge.javals.indexing.FileIndexerRegistry;
import ch.castleridge.javals.indexing.bytecode.BytecodeIndexer;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.source.SourceIndexer;

class ScanCollectorTest {

    @Test
    void snapshotGroupsCountsByExtension() {
        ScanCollector collector = new ScanCollector();
        collector.addFile(".java");
        collector.addFile(".java");
        collector.addFile(".kt");
        collector.addFile(".class");
        collector.addClassFileBytes(100);
        collector.addClassFileBytes(50);

        ScanStats stats = collector.snapshot();
        assertEquals(2, stats.fileCount(".java"));
        assertEquals(1, stats.fileCount(".kt"));
        assertEquals(1, stats.fileCount(".class"));
        assertEquals(3, stats.sourceFileCount());
        assertEquals(150L, stats.classFileBytes());
        assertEquals(".class: 1 files; .java: 2 files; .kt: 1 files",
                stats.formatWithWorkMs(Map.of()));
        // 10 ms across 2 .java → avg 5; 20 ms across 1 .kt → avg 20
        assertEquals(".class: 1 files; .java: 2 files, avg 5 ms; .kt: 1 files, avg 20 ms",
                stats.formatWithWorkMs(Map.of(".java", 10L, ".kt", 20L)));
    }

    @Test
    void dirInputAndScannerRecordPerExtensionCountsAndWorkMs(@TempDir Path dir) throws Exception {
        ScanCollector collector = new ScanCollector();
        Files.writeString(dir.resolve("A.java"), "class A {}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("B.kt"), "class B", StandardCharsets.UTF_8);
        Files.write(dir.resolve("C.class"), new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});

        SourceIndexer java = (path, uri, content, into) -> { };
        BytecodeIndexer bytecode = (path, uri, bytes, into) -> { };
        FileIndexer kotlin = new FileIndexer() {
            @Override
            public String extension() {
                return FileIndexerRegistry.EXT_KT;
            }

            @Override
            public void index(String resourcePath, String sourceUri, byte[] content, Index into) {
            }
        };

        FileIndexerRegistry registry = new FileIndexerRegistry(List.of(
                FileIndexerRegistry.forJava(java),
                FileIndexerRegistry.forClass(bytecode),
                kotlin));
        ScanResult scan = new Scanner(registry).scan(List.of(new DirInput(dir, collector)),
                new InMemoryIndex());

        ScanStats stats = collector.snapshot();
        assertEquals(1, stats.fileCount(".java"));
        assertEquals(1, stats.fileCount(".kt"));
        assertEquals(1, stats.fileCount(".class"));
        assertTrue(scan.failures().isEmpty(), () -> scan.failures().toString());
        assertTrue(scan.indexWorkMsByExtension().containsKey(".java"));
        assertTrue(scan.indexWorkMsByExtension().containsKey(".kt"));
        assertTrue(scan.indexWorkMsByExtension().containsKey(".class"));
    }
}
