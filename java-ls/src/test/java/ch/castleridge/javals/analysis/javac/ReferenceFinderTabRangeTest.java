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
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: lines with leading tabs + a tab after a type name used to report
 * reference ranges shifted onto the following identifier.
 */
class ReferenceFinderTabRangeTest {

    @Test
    void stringFieldTypeRangeIgnoresTabExpansion() throws Exception {
        JrtInput jrt = new JrtInput(java.nio.file.Path.of(System.getProperty("java.home")));
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String fieldLine = "\tpublic final static String\tCAPABILITY_USES_DIRECTIVE\t\t= \"uses\";";
        String source = """
                package com.example;

                public class Constants {
                %s
                }
                """.formatted(fieldLine);

        String docUri = "mem:///Constants.java";
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrt.sourceUri()).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        AnalysisSession session = new JavacWorkspaceCompiler().analyze(docUri, source, index, cp);

        int stringCol = fieldLine.indexOf("String");
        ResolvedSymbol resolved = session.resolveAt(new Position(3, stringCol)).orElseThrow();
        List<Location> refs = session.referencesInUnit(resolved);
        assertFalse(refs.isEmpty(), "expected at least the field-type usage of String");

        Location fieldTypeRef = refs.stream()
                .filter(loc -> loc.getRange().getStart().getLine() == 3)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reference on field line, got: " + refs));

        Range range = fieldTypeRef.getRange();
        assertEquals(stringCol, range.getStart().getCharacter(),
                () -> "start should be UTF-16 index of String, got " + range);
        assertEquals(stringCol + "String".length(), range.getEnd().getCharacter(),
                () -> "end should be UTF-16 index after String, got " + range);

        int capabilityCol = fieldLine.indexOf("CAPABILITY_USES_DIRECTIVE");
        assertTrue(capabilityCol > stringCol);
        assertFalse(
                range.getStart().getCharacter() == capabilityCol
                        && range.getEnd().getCharacter() == capabilityCol + "String".length(),
                "range must not highlight CAPABILITY_USES_DIRECTIVE");
    }
}
