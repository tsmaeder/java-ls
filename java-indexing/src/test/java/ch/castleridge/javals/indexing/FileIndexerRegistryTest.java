/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.bytecode.BytecodeIndexer;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.SourceIndexer;

class FileIndexerRegistryTest {

    @Test
    void defaultsRegisterJavaClassAndKotlin() {
        FileIndexerRegistry registry = FileIndexerRegistry.defaults();
        assertTrue(registry.accepts("Foo.java"));
        assertTrue(registry.accepts("Foo.class"));
        assertTrue(registry.accepts("Foo.kt"));
        assertFalse(registry.accepts("Foo.txt"));
        assertFalse(registry.accepts("package-info.java"));
        assertNotNull(registry.get(".java"));
        assertNotNull(registry.get(".class"));
        assertNotNull(registry.get(".kt"));
    }

    @Test
    void staticHelpersRecognizeSourceAndClassExtensions() {
        assertTrue(FileIndexerRegistry.isIndexableFileName("a/B.kt"));
        assertTrue(FileIndexerRegistry.isSourceFileName("a/B.kt"));
        assertTrue(FileIndexerRegistry.isSourceFileName("a/B.java"));
        assertFalse(FileIndexerRegistry.isSourceFileName("a/B.class"));
        assertEquals(".kt", FileIndexerRegistry.extensionOf("pkg/Hello.kt"));
    }

    @Test
    void scannerRoutesByExtension(@TempDir Path dir) throws Exception {
        AtomicInteger javaCalls = new AtomicInteger();
        AtomicInteger classCalls = new AtomicInteger();
        AtomicInteger ktCalls = new AtomicInteger();

        SourceIndexer java = (path, uri, content, into) -> javaCalls.incrementAndGet();
        BytecodeIndexer bytecode = (path, uri, bytes, into) -> classCalls.incrementAndGet();
        FileIndexer kotlin = new FileIndexer() {
            @Override
            public String extension() {
                return FileIndexerRegistry.EXT_KT;
            }

            @Override
            public void index(String resourcePath, String sourceUri, byte[] content, Index into) {
                ktCalls.incrementAndGet();
            }
        };

        Files.writeString(dir.resolve("A.java"), "class A {}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("B.kt"), "class B", StandardCharsets.UTF_8);
        Files.write(dir.resolve("C.class"), new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Files.writeString(dir.resolve("D.txt"), "ignore", StandardCharsets.UTF_8);

        FileIndexerRegistry registry = new FileIndexerRegistry(List.of(
                FileIndexerRegistry.forJava(java),
                FileIndexerRegistry.forClass(bytecode),
                kotlin));
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner(registry).scanAll(List.of(new DirInput(dir)), index);
        assertTrue(failures.isEmpty(), () -> failures.toString());
        assertEquals(1, javaCalls.get());
        assertEquals(1, classCalls.get());
        assertEquals(1, ktCalls.get());
    }

    @Test
    void javaIndexerStillProducesTypesThroughRegistry() {
        Index index = new InMemoryIndex();
        FileIndexerRegistry.defaults().indexOne(
                "demo/Hello.java",
                "mem:///",
                "Hello.java",
                """
                package demo;
                public class Hello {
                    public void greet() {}
                }
                """.getBytes(StandardCharsets.UTF_8),
                index);
        TypeEntry hello = index.getAll("demo/Hello").stream().findFirst().orElse(null);
        assertNotNull(hello);
        assertTrue(hello instanceof SourceTypeEntry);
        assertEquals(1, hello.methods().length);
    }
}
