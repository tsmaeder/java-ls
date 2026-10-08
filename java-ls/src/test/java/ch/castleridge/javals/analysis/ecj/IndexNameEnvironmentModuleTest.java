/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.internal.compiler.env.IBinaryModule;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
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
    void compilerReusesEnvironmentPerNamespace() {
        EcjWorkspaceCompiler compiler = new EcjWorkspaceCompiler();
        IndexNameEnvironment first = compiler.environmentFor("ns", index, classpath);
        IndexNameEnvironment second = compiler.environmentFor("ns", index, classpath);
        assertSame(first, second);

        String source = """
                package demo;
                class Use { Local local; }
                """;
        AnalysisSession session = compiler.analyze(
                "file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable());
        IndexNameEnvironment after = compiler.environmentFor("ns", index, classpath);
        assertSame(first, after);
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
}
