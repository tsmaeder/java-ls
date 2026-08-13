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
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

class EcjAnalysisSessionReferencesTest {

    /**
     * Types the analysed unit only mentions in passing. They exist to push the
     * lookup environment's type ids well past the initial capacity of its type
     * table, so that a type registered late gets an id that only a live,
     * un-reset type system can resolve.
     */
    private static final String[] FILLER_TYPES = {
        "java.util.ArrayList<String>", "java.util.LinkedList<String>", "java.util.HashMap<String,String>",
        "java.util.TreeMap<String,String>", "java.util.HashSet<String>", "java.util.TreeSet<String>",
        "java.util.ArrayDeque<String>", "java.util.PriorityQueue<String>", "java.util.Vector<String>",
        "java.util.BitSet", "java.util.Random", "java.util.UUID", "java.util.Locale",
        "java.util.Currency", "java.util.TimeZone", "java.util.Calendar", "java.util.Date",
        "java.util.StringJoiner", "java.util.Scanner", "java.util.Timer",
        "java.util.concurrent.ConcurrentHashMap<String,String>", "java.util.concurrent.CountDownLatch",
        "java.util.concurrent.Semaphore", "java.util.concurrent.CyclicBarrier",
        "java.util.concurrent.CompletableFuture<String>", "java.util.concurrent.ThreadLocalRandom",
        "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong",
        "java.util.regex.Pattern", "java.util.zip.CRC32", "java.util.jar.Manifest",
        "java.io.File", "java.io.StringWriter", "java.io.StringReader", "java.io.PrintWriter",
        "java.io.BufferedReader", "java.io.ByteArrayOutputStream", "java.io.CharArrayWriter",
        "java.nio.ByteBuffer", "java.nio.CharBuffer", "java.nio.IntBuffer",
        "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Duration",
        "java.time.Period", "java.time.ZoneId", "java.time.format.DateTimeFormatter",
        "java.math.BigDecimal", "java.math.BigInteger", "java.text.DecimalFormat",
        "java.text.SimpleDateFormat", "java.text.MessageFormat", "java.net.URI",
        "java.net.InetSocketAddress", "java.net.Proxy", "java.security.MessageDigest",
        "java.security.SecureRandom", "java.sql.Timestamp", "java.lang.StringBuilder",
    };

    /**
     * Symbol identities are derived from bindings long after the compilation
     * that produced them. Erasing a generic array parameter registers the
     * erased array with the lookup environment's type system, which therefore
     * has to survive the compilation.
     */
    @Test
    void findsReferencesWhenBindingsErasureRegistersNewTypes() throws Exception {
        IndexedClasspath env = indexJrt();

        StringBuilder source = new StringBuilder("""
                package demo;

                import java.util.ArrayList;
                import java.util.List;

                class Use {
                    List<String> collect(String[] values) {
                        List<String> out = new ArrayList<>();
                        for (String value : values) {
                            out.addAll(List.of(value));
                        }
                        return out;
                    }
                """);
        for (int i = 0; i < FILLER_TYPES.length; i++) {
            source.append("    ").append(FILLER_TYPES[i]).append(" f").append(i).append(";\n");
        }
        source.append("    <T extends java.util.concurrent.BlockingDeque<String>> void tail(T[] items) { }\n");
        source.append("}\n");

        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source.toString(), env.index(), env.classpath());
        assertTrue(session.isUsable());

        // 'String' in "for (String value : values)"
        ResolvedSymbol resolved = session.resolveAt(new Position(8, 17)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());
        assertFalse(resolved.fileLocal());

        List<Location> references = session.findReferencesTo(resolved.identity());
        assertTrue(references.size() >= 4,
                () -> "expected every String reference in the unit, got " + references);
    }

    /**
     * ECJ dispatches {@code String[]} to {@code ArrayTypeReference}, not
     * {@code SingleTypeReference}. Missing that visit drops every array usage.
     */
    @Test
    void findsStringReferencesInArrayTypeUsages() throws Exception {
        IndexedClasspath env = indexJrt();
        String source = """
                package demo;

                class Use {
                    String[] field;
                    void m(String[] a) {
                        String[] local = a;
                        Class<?> c = String[].class;
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, env.index(), env.classpath());
        assertTrue(session.isUsable());

        // 'String' in "String[] field" (0-based col 4 = 'S')
        ResolvedSymbol resolved = session.resolveAt(new Position(3, 4)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());

        List<Location> references = session.findReferencesTo(resolved.identity());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(3, 4, 5, 6), lines,
                () -> "expected String[] field/param/local/class-literal refs, got " + references);
        assertEquals(4, references.size(), () -> "expected exactly 4 String refs, got " + references);
    }

    /**
     * Parameterized type nodes record the outer type on a separate visit overload;
     * type-argument children alone are not enough for references to {@code List}.
     */
    @Test
    void findsParameterizedOuterTypeReferences() throws Exception {
        IndexedClasspath env = indexJrt();
        String source = """
                package demo;

                import java.util.List;

                class Use {
                    List<String> field;
                    void m(List<String> a) {
                        List<String> local = a;
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, env.index(), env.classpath());
        assertTrue(session.isUsable());

        // 'List' in "List<String> field" (0-based col 4 = 'L')
        ResolvedSymbol resolved = session.resolveAt(new Position(5, 4)).orElseThrow();
        assertEquals("List", resolved.identity().simpleName());

        List<Location> references = session.findReferencesTo(resolved.identity());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(lines.contains(2), () -> "expected import java.util.List, got " + references);
        assertTrue(lines.containsAll(Set.of(5, 6, 7)),
                () -> "expected List field/param/local refs, got " + references);
        assertTrue(references.size() >= 4,
                () -> "expected import + three List<> usages, got " + references);
    }

    /**
     * A file that does not compile cleanly must still report the references
     * that did resolve. ECJ's convenience traverse skips units tagged as having
     * errors — which a malformed member declaration does — so the analysis
     * session has to opt out of that behaviour.
     */
    @Test
    void findsReferencesInFileWithErrors() throws Exception {
        IndexedClasspath env = indexJrt();
        String source = """
                package demo;

                class Use {
                    int broken

                    String field;
                    String m(String a) {
                        String local = a;
                        return local;
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, env.index(), env.classpath());
        assertTrue(session.isUsable());
        assertTrue(session.diagnostics().stream()
                        .anyMatch(d -> d.severity() == DiagnosticSeverity.Error),
                () -> "expected the malformed declaration to be reported, got " + session.diagnostics());

        // 'String' in "String field"
        ResolvedSymbol resolved = session.resolveAt(new Position(5, 4)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());

        List<Location> references = session.findReferencesTo(resolved.identity());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(5, 6, 7), lines,
                () -> "expected String refs on field, return type, parameter and local, got " + references);
        assertEquals(4, references.size(), () -> "expected exactly 4 String refs, got " + references);
    }

    /**
     * A static import spells out the declaring type of the imported member, so
     * {@code import static java.lang.String.format} references String. ECJ only
     * resolves the import to the member itself, so the type segment has to be
     * recorded separately.
     */
    @Test
    void findsStringReferenceInStaticImportQualifier() throws Exception {
        IndexedClasspath env = indexJrt();
        String source = """
                package demo;

                import static java.lang.String.format;
                import static java.lang.String.CASE_INSENSITIVE_ORDER;
                import static java.lang.Integer.*;

                class Use {
                    Object m() {
                        return format("%s", CASE_INSENSITIVE_ORDER) + parseInt("1");
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, env.index(), env.classpath());
        assertTrue(session.isUsable());

        // 'String' in the "java.lang.String.format" import qualifier
        ResolvedSymbol resolved = session.resolveAt(new Position(2, 25)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());

        List<Location> references = session.findReferencesTo(resolved.identity());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(Set.of(2, 3), lines,
                () -> "expected String refs in both static import qualifiers, got " + references);

        // An on-demand static import resolves to the type itself
        ResolvedSymbol onDemand = session.resolveAt(new Position(4, 25)).orElseThrow();
        assertEquals("Integer", onDemand.identity().simpleName());
    }

    private static IndexedClasspath indexJrt() throws Exception {
        InMemoryIndex index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri().toString())), false);
        return new IndexedClasspath(index, classpath);
    }

    private record IndexedClasspath(InMemoryIndex index, ClasspathOrder classpath) {}
}
