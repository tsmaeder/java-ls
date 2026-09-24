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

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstDeclarationLocator;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLocatorNestedClassTest {

    @Test
    void base64EncoderImportNavigatesToInnerClassInJdkSources() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip),
                "JDK src.zip not present");

        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String source = """
                import java.util.Base64.Encoder;

                class Use {
                }
                """;
        String docUri = "mem:///Use.java";
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrtUri).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        Map<String, String> attached = Map.of(jrtUri, srcZip.toUri().toString());
        AnalysisSession session = new JavacWorkspaceCompiler(
                new AstDeclarationLocator(JavacDietSources::lower), attached)
                .analyze(docUri, source, index, cp);

        String importLine = "import java.util.Base64.Encoder;";
        ResolvedSymbol resolved = session.resolveAt(new Position(0, importLine.indexOf("Encoder"))).orElseThrow();
        Location location = session.definitionOf(resolved).orElse(null);
        assertNotNull(location, "go-to-definition for Base64.Encoder must resolve");
        assertTrue(location.getUri().contains("Base64.java"),
                () -> "definition should open Base64.java, got: " + location.getUri());
    }
}
