/**
 * Copyright 2026 by Castle Ridge Software
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ch.castleridge.javals.analysis.ecj.EcjDietSources;
import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.JavacDietSources;
import ch.castleridge.javals.analysis.javac.JavacWorkspaceCompiler;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * Call hierarchy (prepare / incoming / outgoing) for both compiler backends.
 */
class CallHierarchyTest {

    @TempDir
    Path workspace;

    private InMemoryIndex index;
    private ClasspathOrder classpath;
    private Map<String, String> attachedSources;
    private String workspaceUri;
    private String aUri;
    private String bUri;
    private String cUri;
    private String ctorUri;
    private String refUri;

    @BeforeEach
    void setUp() throws Exception {
        index = new InMemoryIndex();
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip), "JDK src.zip not present");

        JrtInput jrt = new JrtInput(jdk);
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());

        workspaceUri = workspace.toUri().toString();
        if (!workspaceUri.endsWith("/")) {
            workspaceUri = workspaceUri + "/";
        }
        classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(workspaceUri),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        attachedSources = Map.of(jrt.sourceUri(), srcZip.toUri().toString());

        writeAndIndex("demo/A.java", """
                package demo;
                public class A {
                    public void m() {}
                }
                """);
        writeAndIndex("demo/B.java", """
                package demo;
                public class B {
                    public void caller() {
                        new A().m();
                    }
                }
                """);
        writeAndIndex("demo/C.java", """
                package demo;
                public class C {
                    public void other() {
                        new A().m();
                    }
                }
                """);
        writeAndIndex("demo/Ctor.java", """
                package demo;
                public class Ctor {
                    public Ctor() {}
                    public static Ctor make() {
                        return new Ctor();
                    }
                }
                """);
        writeAndIndex("demo/Refs.java", """
                package demo;
                import java.util.function.Consumer;
                public class Refs {
                    public void target() {}
                    public void use() {
                        Consumer<Void> c = x -> target();
                        Runnable r = this::target;
                    }
                }
                """);

        aUri = workspace.resolve("demo/A.java").toUri().toString();
        bUri = workspace.resolve("demo/B.java").toUri().toString();
        cUri = workspace.resolve("demo/C.java").toUri().toString();
        ctorUri = workspace.resolve("demo/Ctor.java").toUri().toString();
        refUri = workspace.resolve("demo/Refs.java").toUri().toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javac", "ecj"})
    void prepareIncomingAndOutgoing(String backend) {
        AnalysisSession aSession = analyze(backend, aUri, read("demo/A.java"));
        assertTrue(aSession.isUsable());

        CallHierarchyItem prepared = aSession.prepareCallHierarchy(positionOf("demo/A.java", "void m"))
                .orElseThrow();
        assertEquals("m", prepared.getName());
        assertEquals("demo.A", prepared.getDetail());

        SymbolKey key = CallHierarchySupport.keyFromItem(prepared).orElseThrow();

        AnalysisSession bSession = analyze(backend, bUri, read("demo/B.java"));
        AnalysisSession cSession = analyze(backend, cUri, read("demo/C.java"));
        List<CallHierarchyIncomingCall> fromB = bSession.incomingCallsInUnit(key);
        List<CallHierarchyIncomingCall> fromC = cSession.incomingCallsInUnit(key);
        assertEquals(Set.of("caller"), callerNames(fromB));
        assertEquals(Set.of("other"), callerNames(fromC));
        assertFalse(fromB.get(0).getFromRanges().isEmpty());
        assertFalse(fromC.get(0).getFromRanges().isEmpty());

        CallHierarchyItem caller = bSession.prepareCallHierarchy(positionOf("demo/B.java", "void caller"))
                .orElseThrow();
        List<CallHierarchyOutgoingCall> outgoing = bSession.outgoingCalls(caller);
        assertTrue(calleeNames(outgoing).contains("m"), () -> "expected m in " + calleeNames(outgoing));
        assertTrue(calleeNames(outgoing).contains("A"), () -> "expected constructor A in " + calleeNames(outgoing));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javac", "ecj"})
    void constructorsAndMethodRefsCountAsCallSites(String backend) {
        AnalysisSession ctorSession = analyze(backend, ctorUri, read("demo/Ctor.java"));
        CallHierarchyItem ctor = ctorSession.prepareCallHierarchy(positionOf("demo/Ctor.java", "public Ctor()"))
                .orElseThrow();
        assertEquals("Ctor", ctor.getName());

        SymbolKey ctorKey = CallHierarchySupport.keyFromItem(ctor).orElseThrow();
        List<CallHierarchyIncomingCall> incoming = ctorSession.incomingCallsInUnit(ctorKey);
        assertEquals(Set.of("make"), callerNames(incoming));

        AnalysisSession refSession = analyze(backend, refUri, read("demo/Refs.java"));
        CallHierarchyItem target = refSession.prepareCallHierarchy(positionOf("demo/Refs.java", "void target"))
                .orElseThrow();
        SymbolKey targetKey = CallHierarchySupport.keyFromItem(target).orElseThrow();
        List<CallHierarchyIncomingCall> refIncoming = refSession.incomingCallsInUnit(targetKey);
        assertEquals(Set.of("use"), callerNames(refIncoming));
        assertTrue(refIncoming.get(0).getFromRanges().size() >= 2,
                () -> "expected lambda + method-ref ranges, got " + refIncoming.get(0).getFromRanges().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javac", "ecj"})
    void prepareOnTypeOrFieldReturnsEmpty(String backend) {
        AnalysisSession session = analyze(backend, aUri, read("demo/A.java"));
        assertTrue(session.prepareCallHierarchy(positionOf("demo/A.java", "class A")).isEmpty());
    }

    private void writeAndIndex(String relativePath, String source) throws Exception {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavacSourceIndexer.index(relativePath, workspaceUri, source, index);
    }

    private String read(String relativePath) {
        try {
            return Files.readString(workspace.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Position positionOf(String relativePath, String needle) {
        String source = read(relativePath);
        int offset = source.indexOf(needle);
        assertTrue(offset >= 0, "needle not found: " + needle);
        // Point at the meaningful identifier inside the needle when possible.
        int nameOffset = needle.contains(" m") ? source.indexOf(" m", offset) + 1
                : needle.contains(" caller") ? source.indexOf(" caller", offset) + 1
                : needle.contains(" target") ? source.indexOf(" target", offset) + 1
                : needle.contains("Ctor()") ? source.indexOf("Ctor()", offset)
                : offset;
        if (needle.startsWith("class ")) {
            nameOffset = source.indexOf("A", offset);
        }
        int line = 0;
        int lastBreak = -1;
        for (int i = 0; i < nameOffset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lastBreak = i;
            }
        }
        return new Position(line, nameOffset - lastBreak - 1);
    }

    private AnalysisSession analyze(String backend, String uri, String source) {
        WorkspaceCompiler compiler = "ecj".equals(backend)
                ? new EcjWorkspaceCompiler(new AstDeclarationLocator(EcjDietSources::lower), attachedSources)
                : new JavacWorkspaceCompiler(new AstDeclarationLocator(JavacDietSources::lower), attachedSources);
        return compiler.analyze(uri, source, index, classpath);
    }

    private static Set<String> callerNames(List<CallHierarchyIncomingCall> calls) {
        return calls.stream().map(c -> c.getFrom().getName()).collect(Collectors.toSet());
    }

    private static Set<String> calleeNames(List<CallHierarchyOutgoingCall> calls) {
        return calls.stream().map(c -> c.getTo().getName()).collect(Collectors.toSet());
    }
}
