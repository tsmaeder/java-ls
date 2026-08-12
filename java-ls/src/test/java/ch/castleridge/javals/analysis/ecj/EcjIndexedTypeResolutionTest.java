package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.ecj.EcjSourceIndexer;

/**
 * Indexing is context free, so a source entry records a reference to a nested
 * type by its simple name (or, for a member select, by a package-less
 * {@code Outer$Inner}) and records a type parameter bound without saying
 * whether it is a class or an interface. Resolving those against the index is
 * the compiler backend's job.
 */
class EcjIndexedTypeResolutionTest {
    private static final String CLASSPATH_URI = "index:///resolution/";

    private InMemoryIndex index;
    private ClasspathOrder classpath;

    @BeforeEach
    void indexJdk() throws Exception {
        index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());
        classpath = new ClasspathOrder(
                List.of(UriClasspathEntry.of(CLASSPATH_URI), UriClasspathEntry.of(jrt.sourceUri().toString())),
                false);
    }

    private void indexSource(String path, String source) {
        EcjSourceIndexer.index(path, CLASSPATH_URI, source, index);
    }

    private void assertNoErrors(String source) {
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
        assertTrue(session.isUsable());
        assertTrue(session.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.Error),
                () -> "Unexpected ECJ diagnostics: " + session.diagnostics());
    }

    /**
     * {@code builder()} returns {@code Builder}, which the index stores as the
     * bare simple name. It has to be resolved as a member type of the
     * declaring class, not as a top-level type in the declaring package.
     */
    @Test
    void resolvesNestedReturnTypeNamedBySimpleName() {
        indexSource("api/Table.java", """
                package api;
                public class Table {
                    public static Builder builder() {
                        return new Builder();
                    }
                    public static class Builder {
                        public Table build() {
                            return new Table();
                        }
                    }
                }
                """);

        assertNoErrors("""
                package demo;
                import api.Table;
                class Use {
                    Table make() {
                        return Table.builder().build();
                    }
                }
                """);
    }

    /** A member type may be inherited rather than declared by the referring type. */
    @Test
    void resolvesNestedTypeInheritedFromSupertype() {
        indexSource("api/Base.java", """
                package api;
                public class Base {
                    public static class Handle {
                        public int id() {
                            return 1;
                        }
                    }
                }
                """);
        indexSource("api/Derived.java", """
                package api;
                public class Derived extends Base {
                    public Handle handle() {
                        return new Handle();
                    }
                }
                """);

        assertNoErrors("""
                package demo;
                import api.Derived;
                class Use {
                    int id() {
                        return new Derived().handle().id();
                    }
                }
                """);
    }

    /**
     * A member select such as {@code Storage.Builder} is indexed as the
     * package-less {@code Storage$Builder}; the package has to be recovered
     * from the declaring unit's imports.
     */
    @Test
    void resolvesQualifiedNestedTypeOfAnImportedType() {
        indexSource("store/Storage.java", """
                package store;
                public class Storage {
                    public static class Builder {
                        public int size() {
                            return 0;
                        }
                    }
                }
                """);
        indexSource("api/Holder.java", """
                package api;
                import store.Storage;
                public class Holder {
                    public Storage.Builder storageBuilder() {
                        return new Storage.Builder();
                    }
                }
                """);

        assertNoErrors("""
                package demo;
                import api.Holder;
                class Use {
                    int size() {
                        return new Holder().storageBuilder().size();
                    }
                }
                """);
    }

    /**
     * Members of an interface are implicitly public and static even though the
     * source declares no modifiers, so they stay visible outside their package.
     */
    @Test
    void treatsInterfaceMembersAsPublicAndStatic() {
        indexSource("api/ColumnPosition.java", """
                package api;
                public sealed interface ColumnPosition
                        permits ColumnPosition.First, ColumnPosition.Last {
                    record First() implements ColumnPosition {}
                    record Last() implements ColumnPosition {}
                }
                """);

        assertNoErrors("""
                package demo;
                import api.ColumnPosition;
                class Use {
                    ColumnPosition first() {
                        return new ColumnPosition.First();
                    }
                }
                """);
    }

    /**
     * A type parameter bounded only by an interface writes an empty class bound
     * ({@code P::Lapi/Policy;}). Declaring the interface as the class bound
     * instead makes ECJ reject valid type arguments.
     */
    @Test
    void acceptsTypeArgumentForInterfaceBoundedTypeParameter() {
        indexSource("api/Policy.java", "package api; public interface Policy<R> {}");
        indexSource("api/RetryPolicy.java", "package api; public interface RetryPolicy<R> extends Policy<R> {}");
        indexSource("api/Failsafe.java", """
                package api;
                public final class Failsafe {
                    public static <R, P extends Policy<R>> Runner<R> with(P policy) {
                        return null;
                    }
                }
                """);
        indexSource("api/Runner.java", "package api; public interface Runner<R> { void run(); }");

        assertNoErrors("""
                package demo;
                import api.Failsafe;
                import api.RetryPolicy;
                class Use {
                    void go(RetryPolicy<?> policy) {
                        Failsafe.with(policy).run();
                    }
                }
                """);
    }
}
