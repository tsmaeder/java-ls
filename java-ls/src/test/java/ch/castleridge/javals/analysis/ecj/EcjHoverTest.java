/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstDeclarationLocator;
import ch.castleridge.javals.analysis.HoverInfo;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcjHoverTest {

    private static final String GREETER_WITH_DOC = """
            package com.example;

            public class Greeter {

                /**
                 * Default display name.
                 */
                public static final String DEFAULT_NAME = "world";

                /**
                 * Greets the given name.
                 * @param name who to greet
                 * @return greeting text
                 */
                public String greet(String name) {
                    return "hello " + name;
                }

                public String greet(int times) {
                    return "hello".repeat(times);
                }
            }
            """;

    @Test
    void inFileHoverIncludesJavadoc(@TempDir Path workspace) throws Exception {
        Index index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri())), false);

        String source = """
                package demo;

                class Use {
                    /**
                     * Adds one.
                     */
                    int inc(int x) {
                        return x + 1;
                    }

                    int m() {
                        return inc(2);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), Map.of())
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable(), () -> session.diagnostics().toString());

        HoverInfo hover = hoverAt(session, tokenAt(source, "inc(2)"));
        assertTrue(hover.signature().contains("inc"));
        assertTrue(hover.hasJavadoc());
        assertTrue(hover.javadoc().contains("Adds one."));
    }

    @Test
    void inFileHoverWithoutJavadocIsSignatureOnly(@TempDir Path workspace) throws Exception {
        Index index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri())), false);

        String source = """
                package demo;

                class Use {
                    int inc(int x) {
                        return x + 1;
                    }

                    int m() {
                        return inc(2);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), Map.of())
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable(), () -> session.diagnostics().toString());

        HoverInfo hover = hoverAt(session, tokenAt(source, "inc(2)"));
        assertTrue(hover.signature().contains("inc"));
        assertFalse(hover.hasJavadoc());
    }

    @Test
    void voidMethodHoverShowsVoidNotError(@TempDir Path workspace) throws Exception {
        Index index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri())), false);

        String source = """
                package demo;

                class Use {
                    public static void main(String[] args) {
                    }

                    void call() {
                        main(null);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), Map.of())
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable(), () -> session.diagnostics().toString());

        HoverInfo decl = hoverAt(session, tokenAt(source, "main(String"));
        assertEquals("void main(java.lang.String[])", decl.signature());

        HoverInfo use = hoverAt(session, tokenAt(source, "main(null)"));
        assertEquals("void main(java.lang.String[])", use.signature());
    }

    @Test
    void attachedSourceHoverIncludesJavadoc(@TempDir Path workspace) throws Exception {
        Dependency dependency = buildDependency(workspace, GREETER_WITH_DOC);
        Index index = new InMemoryIndex();
        JarInput jar = new JarInput(dependency.binaryJar());
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        Map<String, String> attached = Map.of(jar.sourceUri(), dependency.sourcesJar().toUri().toString());

        String source = """
                package demo;

                import com.example.Greeter;

                class Use {
                    String m(Greeter greeter) {
                        return greeter.greet("x");
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), attached)
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable(), () -> session.diagnostics().toString());

        Position at = new Position(
                tokenAt(source, ".greet(").getLine(),
                tokenAt(source, ".greet(").getCharacter() + 1);
        HoverInfo hover = hoverAt(session, at);
        assertTrue(hover.signature().contains("greet"));
        assertTrue(hover.hasJavadoc(), () -> "expected javadoc, got signature=" + hover.signature());
        assertTrue(hover.javadoc().contains("Greets the given name."));
    }

    @Test
    void noAttachedSourceHoverIsSignatureOnly(@TempDir Path workspace) throws Exception {
        Dependency dependency = buildDependency(workspace, GREETER_WITH_DOC);
        Index index = new InMemoryIndex();
        JarInput jar = new JarInput(dependency.binaryJar());
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);

        String source = """
                package demo;

                import com.example.Greeter;

                class Use {
                    String m(Greeter greeter) {
                        return greeter.greet("x");
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), Map.of())
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        assertTrue(session.isUsable(), () -> session.diagnostics().toString());

        Position at = new Position(
                tokenAt(source, ".greet(").getLine(),
                tokenAt(source, ".greet(").getCharacter() + 1);
        HoverInfo hover = hoverAt(session, at);
        assertTrue(hover.signature().contains("greet"));
        assertFalse(hover.hasJavadoc());
    }

    private static HoverInfo hoverAt(AnalysisSession session, Position cursor) {
        ResolvedSymbol resolved = session.resolveAt(cursor).orElseThrow(
                () -> new AssertionError("nothing resolved at " + cursor));
        return session.hoverInfo(resolved).orElseThrow(
                () -> new AssertionError("no hover for " + resolved.key().simpleName()));
    }

    private static Position tokenAt(String source, String token) {
        int offset = source.indexOf(token);
        assertTrue(offset >= 0, "token not found: " + token);
        int line = 0;
        int column = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private record Dependency(Path binaryJar, Path sourcesJar) {}

    private static Dependency buildDependency(Path workspace, String greeterSource) throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("dep/src/com/example"));
        Path sourceFile = sourceDir.resolve("Greeter.java");
        Files.writeString(sourceFile, greeterSource, StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(workspace.resolve("dep/classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8);
        files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
        boolean compiled = compiler.getTask(null, files, d -> {}, List.of(), List.of(),
                files.getJavaFileObjects(sourceFile)).call();
        assertTrue(compiled, "the dependency must compile");
        files.close();

        Path lib = Files.createDirectories(workspace.resolve("lib"));
        Path binaryJar = lib.resolve("dep.jar");
        Path sourcesJar = lib.resolve("dep-sources.jar");
        zip(binaryJar, Map.of(
                "com/example/Greeter.class", Files.readAllBytes(classes.resolve("com/example/Greeter.class"))));
        zip(sourcesJar, Map.of(
                "com/example/Greeter.java", greeterSource.getBytes(StandardCharsets.UTF_8)));
        return new Dependency(binaryJar, sourcesJar);
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
