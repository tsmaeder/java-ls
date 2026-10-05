/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.analysis.ecj.EcjDietSources;
import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

class DeclarationJavadocTest {

    @Test
    void attributedAndDietMethodsExposeJavadoc(@TempDir Path dir) throws Exception {
        String source = """
                package demo;

                class Use {
                    /**
                     * Adds one.
                     */
                    int inc(int x) {
                        return x + 1;
                    }
                }
                """;

        Path file = dir.resolve("Use.java");
        java.nio.file.Files.writeString(file, source);
        MethodDecl diet = findInc(EcjDietSources.lower(file.toUri().toString()));
        assertFalse(diet.javadoc().isBlank());

        Index index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri())), false);
        AstAnalysisSession session = (AstAnalysisSession) new EcjWorkspaceCompiler(
                new AstDeclarationLocator(EcjDietSources::lower), Map.of())
                .analyze("file:///workspace/demo/Use.java", source, index, classpath);
        MethodDecl attributed = findInc(session.compilationUnit());
        assertFalse(attributed.javadoc().isBlank());
    }

    private static MethodDecl findInc(CompilationUnit cu) {
        TypeDecl type = cu.types()[0];
        for (Declaration member : type.members()) {
            if (member instanceof MethodDecl m && m.name() != null && "inc".equals(m.name().name())) {
                return m;
            }
        }
        throw new AssertionError("inc not found");
    }
}
