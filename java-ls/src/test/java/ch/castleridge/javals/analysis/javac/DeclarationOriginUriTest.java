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

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * The open buffer's URI can differ from the indexed {@code resourceUri} by
 * Windows drive-letter case. The declaration must still share the use site's
 * symbol key, which is the index spelling.
 */
class DeclarationOriginUriTest {

    @Test
    void declarationKeyMatchesUsesWhenBufferUriDiffersByDriveLetter() throws Exception {
        String sourceRoot = "file:///D:/src/";
        String stateSource = """
                package demo;

                public enum State {
                    INACTIVE
                }
                """;
        String useSource = """
                package demo;

                class Use {
                    State value = State.INACTIVE;
                }
                """;

        InMemoryIndex index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        JavacSourceIndexer.index("demo/State.java", sourceRoot, stateSource, index);
        TypeEntry state = index.getAll("demo/State").stream().findFirst().orElseThrow();
        String indexedUri = state.resourceUri();
        assertEquals("file:///D:/src/demo/State.java", indexedUri);

        ClasspathOrder classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(sourceRoot), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
        String bufferUri = "file:///d:/src/demo/State.java";
        AnalysisSession declaration = new JavacWorkspaceCompiler().analyze(
                bufferUri, stateSource, index, classpath);
        AnalysisSession use = new JavacWorkspaceCompiler().analyze(
                "file:///D:/src/demo/Use.java", useSource, index, classpath);
        assertTrue(declaration.isUsable(), () -> "declaration: " + declaration.diagnostics());
        assertTrue(use.isUsable(), () -> "use: " + use.diagnostics());

        Position declAt = tokenAt(stateSource, "INACTIVE");
        Position useAt = tokenAt(useSource, "INACTIVE");
        ResolvedSymbol fromDeclaration = declaration.resolveAt(declAt).orElseThrow(
                () -> new AssertionError("no symbol at declaration, diagnostics: " + declaration.diagnostics()));
        ResolvedSymbol fromUse = use.resolveAt(useAt).orElseThrow(
                () -> new AssertionError("no symbol at use, diagnostics: " + use.diagnostics()));
        assertEquals("INACTIVE", fromDeclaration.key().simpleName());
        assertEquals(indexedUri, fromDeclaration.originResourceUri().orElseThrow());
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
}
