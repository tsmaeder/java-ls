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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-side smoke tests for {@link ClassFileIndexer}'s annotation
 * value capture: string arrays (the {@code @SuppressWarnings} shape),
 * primitive elements with explicit + default values, class literals,
 * enum constants, and annotation defaults via {@code @interface ... default}.
 */
class ClassFileIndexerAnnotationValuesTest {

    @Test
    void capturesStringArrayAndPrimitiveAnnotationElements() throws Exception {
        // Use a CLASS-retention custom annotation so the string array is
        // preserved through compilation; SuppressWarnings has SOURCE
        // retention and would be stripped from bytecode.
        Path outDir = Files.createTempDirectory("ann-values");
        try {
            compile(outDir, "Strs.java", """
                    public @interface Strs {
                        String[] value();
                    }
                    """);
            byte[] vBytes = compile(outDir, "V.java", outDir, """
                    public class V {
                        @Strs({"a", "b"})
                        @Deprecated(forRemoval = true, since = "1.2")
                        public void m() {}
                    }
                    """);

            Index index = new InMemoryIndex();
            ClassFileIndexer.index(
                    "index:///V.class",
                    "index:///cp/",
                    vBytes,
                    index);

            TypeEntry v = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "V");
            assertNotNull(v);
            MethodEntry m = Arrays.stream(v.methods()).filter(me -> me.name().equals("m")).findFirst().orElseThrow();

            AnnotationRef strs = findAnnotation(m.annotations(), "Strs");
            assertNotNull(strs, "@Strs must be indexed");
            AnnotationValue val = strs.values().get("value");
            assertInstanceOf(AnnotationValue.Arr.class, val);
            AnnotationValue.Arr arr = (AnnotationValue.Arr) val;
            assertEquals(2, arr.elements().length);
            assertEquals("a", ((AnnotationValue.Str) arr.elements()[0]).value());
            assertEquals("b", ((AnnotationValue.Str) arr.elements()[1]).value());

            AnnotationRef deprecated = findAnnotation(m.annotations(), "java/lang/Deprecated");
            assertNotNull(deprecated, "@Deprecated must be indexed");
            AnnotationValue forRemoval = deprecated.values().get("forRemoval");
            assertInstanceOf(AnnotationValue.Primitive.class, forRemoval);
            assertEquals(Boolean.TRUE, ((AnnotationValue.Primitive) forRemoval).boxed());
            AnnotationValue since = deprecated.values().get("since");
            assertInstanceOf(AnnotationValue.Str.class, since);
            assertEquals("1.2", ((AnnotationValue.Str) since).value());
        } finally {
            cleanup(outDir);
        }
    }

    @Test
    void capturesClassLiteralAndEnumConstantElements() throws Exception {
        Path outDir = Files.createTempDirectory("ann-values-class-enum");
        try {
            compile(outDir, "Pin.java", """
                    public @interface Pin {
                        Class<?> klass();
                        java.lang.annotation.ElementType target() default java.lang.annotation.ElementType.METHOD;
                    }
                    """);
            byte[] userBytes = compile(outDir, "Use.java", outDir, """
                    public class Use {
                        @Pin(klass = String.class, target = java.lang.annotation.ElementType.FIELD)
                        public int f;
                    }
                    """);

            Index index = new InMemoryIndex();
            ClassFileIndexer.index(
                    "index:///Use.class",
                    "index:///cp/",
                    userBytes,
                    index);

            TypeEntry use = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "Use");
            assertNotNull(use);
            AnnotationRef pin = findAnnotation(use.fields()[0].annotations(), "Pin");
            assertNotNull(pin, "@Pin annotation must be indexed on field f");

            AnnotationValue klass = pin.values().get("klass");
            assertInstanceOf(AnnotationValue.ClassRef.class, klass);
            AnnotationValue.ClassRef classRef = (AnnotationValue.ClassRef) klass;
            assertEquals(TypeRef.resolved("java/lang/String"), classRef.type());

            AnnotationValue target = pin.values().get("target");
            assertInstanceOf(AnnotationValue.EnumConst.class, target);
            AnnotationValue.EnumConst enumConst = (AnnotationValue.EnumConst) target;
            assertEquals(TypeRef.resolved("java/lang/annotation/ElementType"), enumConst.enumType());
            assertEquals("FIELD", enumConst.constant());
        } finally {
            cleanup(outDir);
        }
    }

    @Test
    void capturesAnnotationDefaultValueFromBytecode() throws Exception {
        Path outDir = Files.createTempDirectory("ann-default");
        try {
            byte[] bytes = compile(outDir, "WithDefault.java", """
                    public @interface WithDefault {
                        String name() default "anonymous";
                        int level() default 7;
                        String[] tags() default {};
                    }
                    """);

            Index index = new InMemoryIndex();
            ClassFileIndexer.index(
                    "index:///WithDefault.class",
                    "index:///cp/",
                    bytes,
                    index);

            TypeEntry t = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "WithDefault");
            assertNotNull(t);

            MethodEntry name = methodNamed(t, "name");
            assertTrue(name.hasAnnotationDefault());
            assertInstanceOf(AnnotationValue.Str.class, name.annotationDefault());
            assertEquals("anonymous", ((AnnotationValue.Str) name.annotationDefault()).value());

            MethodEntry level = methodNamed(t, "level");
            assertTrue(level.hasAnnotationDefault());
            assertInstanceOf(AnnotationValue.Primitive.class, level.annotationDefault());
            assertEquals(7, ((AnnotationValue.Primitive) level.annotationDefault()).boxed());

            MethodEntry tags = methodNamed(t, "tags");
            assertTrue(tags.hasAnnotationDefault());
            assertInstanceOf(AnnotationValue.Arr.class, tags.annotationDefault());
            assertEquals(0, ((AnnotationValue.Arr) tags.annotationDefault()).elements().length);

            // A method on a normal class has no AnnotationDefault.
            byte[] regularBytes = compile(outDir, "Reg.java", """
                    public class Reg { public void noDefault() {} }
                    """);
            Index regIndex = new InMemoryIndex();
            ClassFileIndexer.index(
                    "index:///Reg.class",
                    "index:///cp/",
                    regularBytes,
                    regIndex);
            MethodEntry reg = methodNamed(ch.castleridge.javals.indexing.IndexTestUtils.get(regIndex, "Reg"), "noDefault");
            assertNull(reg.annotationDefault(), "regular method must not have an AnnotationDefault");
        } finally {
            cleanup(outDir);
        }
    }

    private static byte[] compile(Path outDir, String fileName, String source) throws Exception {
        return compile(outDir, fileName, null, source);
    }

    /**
     * Compile {@code source} into {@code outDir} and return the bytes
     * for the produced class. If {@code classpath} is non-null it is
     * added as the classpath so cross-source references resolve.
     */
    private static byte[] compile(Path outDir, String fileName, Path classpath, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
        if (classpath != null) {
            fm.setLocation(StandardLocation.CLASS_PATH, List.of(classpath.toFile()));
        }
        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("mem:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call(),
                "source " + fileName + " should compile");
        String className = fileName.substring(0, fileName.length() - ".java".length());
        return Files.readAllBytes(outDir.resolve(className + ".class"));
    }

    private static AnnotationRef findAnnotation(AnnotationRef[] refs, String jvmName) {
        for (AnnotationRef r : refs) if (r.jvmName().equals(jvmName)) return r;
        return null;
    }

    private static MethodEntry methodNamed(TypeEntry t, String name) {
        return Arrays.stream(t.methods()).filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static void cleanup(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
