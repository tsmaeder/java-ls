package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

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
        InMemoryIndex index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri().toString())), false);

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
                URI.create("file:///workspace/demo/Use.java"), source.toString(), index, classpath);
        assertTrue(session.isUsable());

        // 'String' in "for (String value : values)"
        ResolvedSymbol resolved = session.resolveAt(new Position(8, 17)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());
        assertFalse(resolved.fileLocal());

        List<Location> references = session.findReferencesTo(resolved.identity());
        assertTrue(references.size() >= 4,
                () -> "expected every String reference in the unit, got " + references);
    }
}
