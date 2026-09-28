/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.AstAnalysisSession;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;

/**
 * Statement recovery must report a syntax error and return, including when a
 * broken method contains a local type. A {@code $} in a qualified type name is
 * legal and must not swallow the following member type.
 */
class EcjStatementRecoveryTest {
    private static final String CLASSPATH_URI = "index:///statement-recovery/";
    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void syntaxErrorStillRecoversEnclosingType() {
        String source = """
                class Use {
                    void m() { int x = ; }
                }
                """;
        AstAnalysisSession session = analyze(source);
        assertTrue(hasError(session), () -> "expected a syntax error, got " + session.diagnostics());
        TypeDecl[] types = session.compilationUnit().types();
        assertEquals(1, types.length);
        assertEquals("Use", types[0].name().name());
    }

    @Test
    void nestedTypeInsideBrokenMethodFinishes() {
        String source = """
                class Outer {
                    static class Mid {
                        void broken() {
                            int x = ;
                            class Local {
                                class Nested {
                                    void m() {}
                                }
                            }
                        }
                    }
                }
                """;
        AstAnalysisSession session = analyze(source);
        assertTrue(hasError(session), () -> "expected a syntax error, got " + session.diagnostics());
        TypeDecl[] types = session.compilationUnit().types();
        assertEquals(1, types.length);
        assertEquals("Outer", types[0].name().name());
        assertNotNull(memberType(types[0], "Mid"));
    }

    @Test
    void dollarInParameterTypeKeepsFollowingMemberEnum() {
        String source = """
                class Outer {
                    void m(io.trino.hadoop.$internal.org.Foo registry) {}
                    public enum Flag { A }
                }
                """;
        AstAnalysisSession session = analyze(source);
        TypeDecl[] types = session.compilationUnit().types();
        assertEquals(1, types.length);
        assertNotNull(memberType(types[0], "Flag"), () -> "members lost, diagnostics " + session.diagnostics());
    }

    private static AstAnalysisSession analyze(String source) {
        AnalysisSession session = assertTimeoutPreemptively(LIMIT, () -> compile(source));
        assertTrue(session instanceof AstAnalysisSession, () -> "expected a lowered unit, got " + session);
        return (AstAnalysisSession) session;
    }

    private static AnalysisSession compile(String source) {
        InMemoryIndex index = new InMemoryIndex();
        EcjSourceIndexer.index("java/lang/Object.java", CLASSPATH_URI, """
                package java.lang;
                public class Object {
                    public Object() {}
                }
                """, index);
        ClasspathOrder classpath = new ClasspathOrder(List.of(UriClasspathEntry.of(CLASSPATH_URI)), false);
        return new EcjWorkspaceCompiler().analyze(
                "file:///workspace/Use.java", source, index, classpath);
    }

    private static boolean hasError(AnalysisSession session) {
        return session.diagnostics().stream().anyMatch(d -> d.severity() == DiagnosticSeverity.Error);
    }

    private static TypeDecl memberType(TypeDecl type, String name) {
        for (Declaration member : type.members()) {
            if (member instanceof TypeDecl nested
                    && nested.name() != null
                    && name.equals(nested.name().name())) {
                return nested;
            }
        }
        return null;
    }
}
