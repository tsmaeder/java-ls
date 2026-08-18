/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.bytecode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileIndexerGenericThrowsTest {

    static Stream<BytecodeIndexer> indexers() {
        return Stream.of(BytecodeIndexer.asm(), BytecodeIndexer.turbine());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void genericThrowsTypeVariableIndexedFromSignature(BytecodeIndexer indexer) throws Exception {
        Path outDir = Files.createTempDirectory("generic-throws-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Sneaky.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            class Sneaky {
                                @SuppressWarnings("unchecked")
                                static <E extends Throwable> void rethrow(Throwable t) throws E {
                                    throw (E) t;
                                }
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new InMemoryIndex();
            indexer.index(
                    "index:///Sneaky.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("Sneaky.class")),
                    index);

            TypeEntry sneaky = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "Sneaky");
            assertNotNull(sneaky);
            MethodEntry rethrow = Arrays.stream(sneaky.methods())
                    .filter(m -> m.name().equals("rethrow"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, rethrow.throwsTypes().length);
            Type thrown = rethrow.throwsTypes()[0];
            assertInstanceOf(Type.TypeVariable.class, thrown);
            assertEquals("E", ((Type.TypeVariable) thrown).name());
        } finally {
            Files.walk(outDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void erasedThrowsUsedWhenSignatureHasNoThrowsClause(BytecodeIndexer indexer) throws Exception {
        Path outDir = Files.createTempDirectory("erased-throws-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(
                    null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Throws.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            class Throws {
                                static void fail() throws java.io.IOException {}
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new InMemoryIndex();
            indexer.index(
                    "index:///Throws.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("Throws.class")),
                    index);

            TypeEntry throwsClass = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "Throws");
            assertNotNull(throwsClass);
            MethodEntry fail = Arrays.stream(throwsClass.methods())
                    .filter(m -> m.name().equals("fail"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, fail.throwsTypes().length);
            assertInstanceOf(TypeRef.Resolved.class, fail.throwsTypes()[0]);
            assertEquals("java/io/IOException", ((TypeRef.Resolved) fail.throwsTypes()[0]).jvmBinaryName());
        } finally {
            Files.walk(outDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
