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
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassFileIndexerVarargsTest {

    static Stream<BytecodeIndexer> indexers() {
        return Stream.of(BytecodeIndexer.asm(), BytecodeIndexer.turbine());
    }

    @ParameterizedTest
    @MethodSource("indexers")
    void varargsMethodRetainsAccVarargsInIndex(BytecodeIndexer indexer) throws Exception {
        Path outDir = Files.createTempDirectory("vararg-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///V.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return "class V { static void all(int... xs) {} }";
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new InMemoryIndex();
            indexer.index(
                    "index:///V.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("V.class")),
                    index);

            TypeEntry v = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "V");
            assertNotNull(v);
            MethodEntry all = Arrays.stream(v.methods()).filter(m -> m.name().equals("all")).findFirst().orElseThrow();
            assertTrue(all.varargs(), "varargs bit should be captured on MethodEntry");
            assertTrue((all.modifiers() & Opcodes.ACC_VARARGS) != 0,
                    "modifiers should retain ACC_VARARGS from classfile");
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
    void nestedStaticTypeRetainsAccStaticFromInnerClasses(BytecodeIndexer indexer) throws Exception {
        Path outDir = Files.createTempDirectory("inner-static-index");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.getDefault(), java.nio.charset.StandardCharsets.UTF_8);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Outer.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            class Outer {
                                static class StaticNested {}
                                class Inner {}
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());

            Index index = new InMemoryIndex();
            indexer.index(
                    "index:///Outer.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("Outer.class")),
                    index);
            indexer.index(
                    "index:///Outer$StaticNested.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("Outer$StaticNested.class")),
                    index);
            indexer.index(
                    "index:///Outer$Inner.class",
                    "index:///cp/",
                    Files.readAllBytes(outDir.resolve("Outer$Inner.class")),
                    index);

            TypeEntry staticNested = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "Outer$StaticNested");
            TypeEntry inner = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "Outer$Inner");
            assertNotNull(staticNested);
            assertNotNull(inner);
            assertTrue((staticNested.modifiers() & Opcodes.ACC_STATIC) != 0,
                    "static nested class must retain ACC_STATIC from InnerClasses");
            assertTrue((inner.modifiers() & Opcodes.ACC_STATIC) == 0,
                    "non-static inner class must not be marked static");
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
