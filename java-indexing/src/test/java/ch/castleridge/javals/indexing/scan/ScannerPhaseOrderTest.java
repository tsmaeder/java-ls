/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.FileIndexerRegistry;
import ch.castleridge.javals.indexing.bytecode.BytecodeIndexer;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.source.SourceIndexer;

class ScannerPhaseOrderTest {

    @Test
    void partitionOrdersJdkThenDirsThenJarsRegardlessOfInputOrder() {
        JarInput jar = new JarInput(Path.of("dep.jar"));
        DirInput dir = new DirInput(Path.of("src"));
        JrtInput jrt = new JrtInput(Path.of("/jdk"));

        Scanner.PhasePartition phases = Scanner.partition(List.of(jar, dir, jrt));

        assertEquals(List.of(jrt.sourceUri()), uris(phases.jdk()));
        assertEquals(List.of(dir.sourceUri()), uris(phases.dirs()));
        assertEquals(List.of(jar.sourceUri()), uris(phases.jars()));

        assertEquals(1, phases.jdk().get(0).index());
        assertEquals(2, phases.dirs().get(0).index());
        assertEquals(3, phases.jars().get(0).index());
        assertEquals(3, phases.jdk().get(0).total());
        assertEquals(3, phases.dirs().get(0).total());
        assertEquals(3, phases.jars().get(0).total());
    }

    @Test
    void scanMergesJdkBeforeDirsBeforeJars(@TempDir Path tempDir) throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("A.java"), "class A {}", StandardCharsets.UTF_8);

        Path jarPath = tempDir.resolve("dep.jar");
        writeJarEntry(jarPath, "B.java", "class B {}");

        DirInput dir = new DirInput(srcDir);
        JarInput jar = new JarInput(jarPath);
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));

        SourceIndexer noopSource = (path, uri, content, into) -> { };
        BytecodeIndexer noopBytecode = (path, uri, bytes, into) -> { };
        FileIndexerRegistry registry = FileIndexerRegistry.of(noopSource, noopBytecode);

        List<String> firstSeenProgress = new ArrayList<>();
        ScanProgressListener progress = (sourceUri, sourceIndex, sourceCount, filesInSource) -> {
            synchronized (firstSeenProgress) {
                if (!firstSeenProgress.contains(sourceUri)) {
                    firstSeenProgress.add(sourceUri);
                }
            }
        };

        // Progress first-seen order tracks phase starts; noop indexers keep
        // merges empty so addAll cannot be used as the order signal.
        InMemoryIndex index = new InMemoryIndex();
        ScanResult scan = new Scanner(registry).scan(List.of(jar, dir, jrt), index, progress);

        assertTrue(scan.failures().isEmpty(), () -> scan.failures().toString());
        List<String> seen;
        synchronized (firstSeenProgress) {
            seen = List.copyOf(firstSeenProgress);
        }
        assertEquals(List.of(jrt.sourceUri(), dir.sourceUri(), jar.sourceUri()), seen);

        assertTrue(scan.jdkMs() >= 0);
        assertTrue(scan.sourceFilesMs() >= 0);
        assertTrue(scan.jarFilesMs() >= 0);
        assertTrue(scan.elapsedMs() >= scan.jdkMs());
    }

    private static List<String> uris(List<Scanner.SourceSlot> slots) {
        return slots.stream().map(s -> s.source().sourceUri()).toList();
    }

    private static void writeJarEntry(Path jar, String entryName, String contents) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(contents.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
