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
package ch.castleridge.javals.analysis.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

/**
 * A declaration opened from a dependency's sources jar must share the
 * workspace use's symbol key, which is the indexed {@code .class} URI.
 */
class AttachedSourceReferenceTest {

    private static final String GREETER_SOURCE = """
            package com.example;

            public class Greeter {
                public String greet(String name) {
                    return "hello " + name;
                }
            }
            """;

    @Test
    void attachedSourceDeclarationKeyMatchesWorkspaceUse(@TempDir Path workspace) throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("dep/src/com/example"));
        Path sourceFile = sourceDir.resolve("Greeter.java");
        Files.writeString(sourceFile, GREETER_SOURCE, StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(workspace.resolve("dep/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
        assertTrue(javac.getTask(null, files, d -> {}, List.of(), List.of(),
                files.getJavaFileObjects(sourceFile)).call(), "the dependency must compile");
        files.close();

        Path lib = Files.createDirectories(workspace.resolve("lib"));
        Path binaryJar = lib.resolve("dep.jar");
        Path sourcesJar = lib.resolve("dep-sources.jar");
        zip(binaryJar, Map.of(
                "com/example/Greeter.class", Files.readAllBytes(classes.resolve("com/example/Greeter.class"))));
        zip(sourcesJar, Map.of(
                "com/example/Greeter.java", GREETER_SOURCE.getBytes(StandardCharsets.UTF_8)));

        InMemoryIndex index = new InMemoryIndex();
        JarInput jar = new JarInput(binaryJar);
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        Map<String, String> attached = Map.of(jar.sourceUri(), sourcesJar.toUri().toString());
        JavacWorkspaceCompiler compiler =
                new JavacWorkspaceCompiler(new AstDeclarationLocator(JavacDietSources::lower), attached);

        String attachedUri = "jar:" + sourcesJar.toUri() + "!/com/example/Greeter.java";
        String useSource = """
                package demo;

                import com.example.Greeter;

                class Use {
                    String m(Greeter greeter) {
                        return greeter.greet("x");
                    }
                }
                """;
        AnalysisSession declaration = compiler.analyze(attachedUri, GREETER_SOURCE, index, classpath);
        AnalysisSession use = compiler.analyze("file:///workspace/demo/Use.java", useSource, index, classpath);
        assertTrue(declaration.isUsable(), () -> "declaration: " + declaration.diagnostics());
        assertTrue(use.isUsable(), () -> "use: " + use.diagnostics());

        TypeEntry greeter = index.getAll("com/example/Greeter").stream().findFirst().orElseThrow();
        Position declAt = tokenAt(GREETER_SOURCE, "greet(");
        Position dot = tokenAt(useSource, ".greet(");
        Position useAt = new Position(dot.getLine(), dot.getCharacter() + 1);
        ResolvedSymbol fromDeclaration = declaration.resolveAt(declAt).orElseThrow(
                () -> new AssertionError("no symbol at declaration, diagnostics: " + declaration.diagnostics()));
        ResolvedSymbol fromUse = use.resolveAt(useAt).orElseThrow(
                () -> new AssertionError("no symbol at use, diagnostics: " + use.diagnostics()));
        assertEquals("greet", fromDeclaration.key().simpleName());
        assertEquals(greeter.resourceUri(), fromDeclaration.originResourceUri().orElseThrow());
        assertTrue(fromDeclaration.key().matches(fromUse.key()),
                () -> "declaration " + fromDeclaration.key() + " vs use " + fromUse.key());

        assertTrue(declaration.findReferencesTo(fromUse.key()).stream()
                        .anyMatch(loc -> loc.getRange().getStart().getLine() == declAt.getLine()),
                () -> "use key should find the declaration, got " + declaration.findReferencesTo(fromUse.key()));
        assertTrue(use.findReferencesTo(fromDeclaration.key()).stream()
                        .anyMatch(loc -> loc.getRange().getStart().getLine() == useAt.getLine()),
                () -> "declaration key should find the use, got " + use.findReferencesTo(fromDeclaration.key()));
    }

    private static Position tokenAt(String source, String token) {
        int offset = source.indexOf(token);
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
