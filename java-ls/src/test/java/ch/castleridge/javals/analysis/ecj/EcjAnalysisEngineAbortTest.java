package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

/**
 * A compile that dies mid-resolution (here: the index throws while ECJ looks
 * up a type used in a method body) must still surface the failure as an error
 * diagnostic instead of silently returning a half-resolved unit.
 */
class EcjAnalysisEngineAbortTest {

    private static final String POISONED_TYPE = "java/util/Scanner";

    @Test
    void midCompileFailureSurfacesAsErrorDiagnostic() throws Exception {
        InMemoryIndex jrtIndex = new InMemoryIndex();
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jrt), jrtIndex).isEmpty());
        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri().toString())), false);
        Index poisoned = new PoisonedIndex(jrtIndex);

        String source = """
                package demo;

                class Use {
                    String field;
                    void m() {
                        String early = "x";
                        java.util.Scanner scanner = null;
                        String late = early;
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler().analyze(
                URI.create("file:///workspace/demo/Use.java"), source, poisoned, classpath);

        assertTrue(session.isUsable());
        assertTrue(session.diagnostics().stream()
                        .anyMatch(d -> d.severity() == DiagnosticSeverity.Error),
                () -> "expected the mid-compile failure to be reported, got " + session.diagnostics());

        // 'String' in "String field"
        ResolvedSymbol resolved = session.resolveAt(new Position(3, 4)).orElseThrow();
        assertEquals("String", resolved.identity().simpleName());

        List<Location> references = session.findReferencesTo(resolved.identity());
        Set<Integer> lines = references.stream()
                .map(loc -> loc.getRange().getStart().getLine())
                .collect(Collectors.toSet());
        assertTrue(lines.containsAll(Set.of(3, 5)),
                () -> "expected String refs before the failure point to survive, got " + references);
    }

    /** Forwards everything to the delegate but throws for one JVM name. */
    private static final class PoisonedIndex implements Index {
        private final Index delegate;

        PoisonedIndex(Index delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<TypeEntry> getAll(String jvmName) {
            if (POISONED_TYPE.equals(jvmName)) {
                throw new IllegalStateException("poisoned index entry: " + jvmName);
            }
            return delegate.getAll(jvmName);
        }

        @Override
        public void addChangedListener(Runnable listener) {
            delegate.addChangedListener(listener);
        }

        @Override
        public void registerBloom(String resourceUri, IdentifierBloomFilter filter) {
            delegate.registerBloom(resourceUri, filter);
        }

        @Override
        public Map<String, IdentifierBloomFilter> bloomFilters() {
            return delegate.bloomFilters();
        }

        @Override
        public void add(TypeEntry entry) {
            delegate.add(entry);
        }

        @Override
        public void addAll(Index other) {
            delegate.addAll(other);
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public boolean contains(String jvmName) {
            return delegate.contains(jvmName);
        }

        @Override
        public boolean hasPackage(String packageJvm) {
            return delegate.hasPackage(packageJvm);
        }

        @Override
        public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
            return delegate.listPackage(packageJvm, recurse);
        }

        @Override
        public List<TypeEntry> searchTypesBySimpleNamePrefix(String prefix, int limit) {
            return delegate.searchTypesBySimpleNamePrefix(prefix, limit);
        }

        @Override
        public Collection<TypeEntry> all() {
            return delegate.all();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public int entryCount() {
            return delegate.entryCount();
        }

        @Override
        public void addModule(ModuleEntry module) {
            delegate.addModule(module);
        }

        @Override
        public List<ModuleEntry> getAllModules(String moduleName) {
            return delegate.getAllModules(moduleName);
        }

        @Override
        public ModuleEntry getModule(String moduleName) {
            return delegate.getModule(moduleName);
        }

        @Override
        public Collection<ModuleEntry> allModules() {
            return delegate.allModules();
        }

        @Override
        public int moduleCount() {
            return delegate.moduleCount();
        }
    }
}
