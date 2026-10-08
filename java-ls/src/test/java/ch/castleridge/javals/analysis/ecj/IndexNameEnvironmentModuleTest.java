/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.internal.compiler.env.IBinaryModule;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

class IndexNameEnvironmentModuleTest {
    private static final String CLASSPATH_URI = "index:///module-env/";

    private static InMemoryIndex index;
    private static ClasspathOrder classpath;
    private static String jrtUri;

    @BeforeAll
    static void indexJdk() throws Exception {
        index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        jrtUri = jrt.sourceUri().toString();
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        JavacSourceIndexer.index(
                "demo/Local.java",
                CLASSPATH_URI,
                "package demo; public class Local {}",
                index);
        // Hollow intermediate parents: types only under demo.util.nested, none in
        // demo.util itself (mirrors Trino's io.trino.plugin → …base.metrics).
        JavacSourceIndexer.index(
                "demo/util/nested/Thing.java",
                CLASSPATH_URI,
                "package demo.util.nested; public class Thing {}",
                index);
        classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrtUri)),
                false);
    }

    @Test
    void getModuleReturnsBinaryModuleForJavaBase() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        IModule module = env.getModule("java.base".toCharArray());
        assertNotNull(module);
        assertTrue(module instanceof IBinaryModule);
        assertTrue(String.valueOf(module.name()).equals("java.base"));
    }

    @Test
    void findTypeAnyFindsJdkAndLocal() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        NameEnvironmentAnswer object = env.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(object);
        assertNotNull(object.getBinaryType().getModule());

        NameEnvironmentAnswer local = env.findType(
                new char[][] { "demo".toCharArray(), "Local".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(local);
        assertNull(local.getBinaryType().getModule());
    }

    @Test
    void findTypeNamedExcludesUnnamedTypes() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        assertNull(env.findType(
                new char[][] { "demo".toCharArray(), "Local".toCharArray() },
                "java.base".toCharArray()));
        assertNotNull(env.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                "java.base".toCharArray()));
    }

    @Test
    void findTypeUnnamedSeesExportedNamedModuleTypes() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        assertNotNull(env.findType(
                new char[][] { "demo".toCharArray(), "Local".toCharArray() },
                ModuleBinding.UNNAMED));
        // Unnamed-module clients can read unqualified exports (java.lang).
        assertNotNull(env.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                ModuleBinding.UNNAMED));
    }

    @Test
    void compilerReusesLookupCachePerNamespace() {
        EcjWorkspaceCompiler compiler = new EcjWorkspaceCompiler();
        NamespaceLookupCache first = compiler.lookupCacheFor("ns", index, classpath);
        NamespaceLookupCache second = compiler.lookupCacheFor("ns", index, classpath);
        assertSame(first, second);

        String source = """
                package demo;
                class Use { Local local; }
                """;
        AnalysisSession session = compiler.analyze(
                "file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable());
        NamespaceLookupCache after = compiler.lookupCacheFor("ns", index, classpath);
        assertSame(first, after);
    }

    @Test
    void sequentialAnalyzesDoNotShareAnswerMaps() {
        EcjWorkspaceCompiler compiler = new EcjWorkspaceCompiler();
        IndexNameEnvironment first = compiler.newEnvironment("ns", index, classpath);
        first.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                ModuleBinding.ANY);
        assertTrue(first.answerCount() > 0);

        IndexNameEnvironment second = compiler.newEnvironment("ns", index, classpath);
        assertNotSame(first, second);
        assertSame(first.lookup(), second.lookup());
        assertEquals(0, second.answerCount());
    }

    @Test
    void referencesStickyEnvironmentReusesUntilNamespaceChanges() {
        EcjWorkspaceCompiler compiler = new EcjWorkspaceCompiler();
        EcjWorkspaceCompiler.StickyEnvironment first = compiler.environmentForReferences(
                "file:///a/A.java", index, classpath, null);
        first.environment().findType(
                new char[][] { "demo".toCharArray(), "Local".toCharArray() },
                ModuleBinding.ANY);
        int answers = first.environment().answerCount();
        assertTrue(answers > 0);

        EcjWorkspaceCompiler.StickyEnvironment sameNs = compiler.environmentForReferences(
                "file:///a/B.java", index, classpath, first);
        assertSame(first, sameNs);
        assertEquals(answers, sameNs.environment().answerCount());

        // Without mbt, environmentForReferences uses unrestricted "". Pretend the
        // worker was on another namespace so the next call must drop answers.
        EcjWorkspaceCompiler.StickyEnvironment previous =
                new EcjWorkspaceCompiler.StickyEnvironment("other-ns", first.environment());
        EcjWorkspaceCompiler.StickyEnvironment switched = compiler.environmentForReferences(
                "file:///b/C.java", index, classpath, previous);
        assertNotSame(previous.environment(), switched.environment());
        assertEquals(0, previous.environment().answerCount(),
                "prior env answers cleared on namespace switch");
        assertSame(compiler.lookupCacheFor("", index, classpath), switched.environment().lookup());
        switched.release();
    }

    @Test
    void getModuleHitsSharedLookupCacheAcrossEnvironments() {
        EcjWorkspaceCompiler compiler = new EcjWorkspaceCompiler();
        IndexNameEnvironment first = compiler.newEnvironment("ns", index, classpath);
        IModule module = first.getModule("java.base".toCharArray());
        assertNotNull(module);
        assertEquals(1, first.lookup().moduleCacheSize());

        IndexNameEnvironment second = compiler.newEnvironment("ns", index, classpath);
        assertSame(module, second.getModule("java.base".toCharArray()));
        assertEquals(1, second.lookup().moduleCacheSize());
    }

    @Test
    void isPackageSeesJdkAndHollowUnnamedParents() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        assertTrue(env.isPackage(null, "java".toCharArray()));
        assertTrue(env.isPackage(
                new char[][] { "java".toCharArray() }, "lang".toCharArray()));
        assertTrue(env.isPackage(
                new char[][] { "demo".toCharArray() }, "util".toCharArray()),
                "demo.util should exist as parent of demo.util.nested");
        assertTrue(env.isPackage(
                new char[][] { "demo".toCharArray(), "util".toCharArray() },
                "nested".toCharArray()));
        assertFalse(env.isPackage(
                new char[][] { "demo".toCharArray() }, "missing".toCharArray()));
    }

    @Test
    void getModulesDeclaringPackageSeesHollowUnnamedParents() {
        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        char[][] util = env.getModulesDeclaringPackage(
                new char[][] { "demo".toCharArray(), "util".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(util, "demo.util should exist as parent of demo.util.nested");
        assertTrue(Arrays.stream(util).anyMatch(m -> m == ModuleBinding.UNNAMED),
                () -> "expected UNNAMED among " + Arrays.deepToString(
                        Arrays.stream(util).map(String::valueOf).toArray()));

        char[][] nested = env.getModulesDeclaringPackage(
                new char[][] {
                        "demo".toCharArray(), "util".toCharArray(), "nested".toCharArray()
                },
                ModuleBinding.ANY);
        assertNotNull(nested);

        char[][] javaLang = env.getModulesDeclaringPackage(
                new char[][] { "java".toCharArray(), "lang".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(javaLang);
        assertTrue(Arrays.stream(javaLang)
                .anyMatch(m -> "java.base".equals(String.valueOf(m))));
    }

    @Test
    void importThroughHollowUnnamedParentResolves() {
        String source = """
                package demo;
                import demo.util.nested.Thing;
                class UseThing { Thing t; }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/demo/UseThing.java", source, index, classpath);
        assertTrue(session.isUsable());
        assertFalse(session.diagnostics().stream()
                        .anyMatch(d -> d.message() != null
                                && d.message().contains("cannot be resolved")),
                () -> "Unexpected unresolved import: " + session.diagnostics());
    }

    @Test
    void findTypeAnyPrefersJavaBaseOverShadowingUnnamedObject() {
        String shadowUri = "index:///shadow-object/";
        InMemoryIndex shadowed = new InMemoryIndex();
        assertTrue(new Scanner().scanAll(
                List.of(new JrtInput(Path.of(System.getProperty("java.home")))), shadowed).isEmpty());
        shadowed.add(new ClassFileTypeEntry(
                "java/lang/Object.class",
                shadowUri,
                "java/lang/Object",
                0x0001,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                new MethodEntry[] {
                        new MethodEntry(
                                0x0001,
                                "<init>",
                                Type.Primitive.VOID,
                                EmptyArrays.TYPE,
                                EmptyArrays.TYPE,
                                EmptyArrays.ANNOTATION_REF)
                },
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF));
        JavacSourceIndexer.index(
                "demo/User.java",
                shadowUri,
                "package demo; public class User {}",
                shadowed);
        // Shadow jar ahead of JRT so classpath.pick alone would choose the fake Object.
        ClasspathOrder order = new ClasspathOrder(
                List.of(UriClasspathEntry.of(shadowUri), UriClasspathEntry.of(jrtUri)),
                false);
        IndexNameEnvironment env = new IndexNameEnvironment(shadowed, order);
        NameEnvironmentAnswer object = env.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(object);
        assertEquals("java.base", String.valueOf(object.getBinaryType().getModule()));

        char[][] javaLang = env.getModulesDeclaringPackage(
                new char[][] { "java".toCharArray(), "lang".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(javaLang);
        assertTrue(Arrays.stream(javaLang)
                .anyMatch(m -> "java.base".equals(String.valueOf(m))));
        assertFalse(Arrays.stream(javaLang).anyMatch(m -> m == ModuleBinding.UNNAMED),
                () -> "java.lang must not report UNNAMED when owned by java.base: "
                        + Arrays.deepToString(Arrays.stream(javaLang).map(String::valueOf).toArray()));
    }

    @Test
    void moduleModeAnalyzeKeepsObjectFromJavaBase() {
        String source = """
                package demo;
                class Use { Local local; Object o = local; }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                "file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable());
        assertFalse(session.diagnostics().stream()
                        .anyMatch(d -> d.severity() == DiagnosticSeverity.Error
                                && d.message() != null
                                && d.message().toLowerCase().contains("object")),
                () -> "Unexpected Object-related errors under module mode: " + session.diagnostics());

        IndexNameEnvironment env = new IndexNameEnvironment(index, classpath);
        NameEnvironmentAnswer object = env.findType(
                new char[][] { "java".toCharArray(), "lang".toCharArray(), "Object".toCharArray() },
                ModuleBinding.ANY);
        assertNotNull(object);
        assertEquals("java.base", String.valueOf(object.getBinaryType().getModule()));
    }
}
