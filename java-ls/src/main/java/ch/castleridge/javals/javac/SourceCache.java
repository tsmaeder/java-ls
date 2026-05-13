package ch.castleridge.javals.javac;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;

import ch.castleridge.javals.indexing.index.InMemorySource;

/**
 * Bounded LRU cache of parsed (but not analysed) Java source files, keyed
 * by their resource URI string. Used by {@link SymbolLocator} to turn a
 * {@link ch.castleridge.javals.indexing.model.TypeEntry#resourceUri()}
 * into AST positions without re-parsing on every {@code definition}
 * request.
 *
 * <p>Reads bytes through {@link URL#openStream()} so it transparently
 * supports {@code file:}, {@code jar:} and {@code jrt:} URIs - the same
 * URI shapes the indexer already stamps on its entries. Parse failures
 * yield an empty {@link Optional} and are not cached.
 */
public final class SourceCache {

    /** Holds the parsed shape of a single source file. */
    public record ParsedSource(URI uri,
                               JavacTask task,
                               CompilationUnitTree cu,
                               Trees trees,
                               LineMap lineMap,
                               SourcePositions positions) {}

    private static final int DEFAULT_CAPACITY = 64;

    private final Map<String, ParsedSource> cache;

    public SourceCache() {
        this(DEFAULT_CAPACITY);
    }

    public SourceCache(int capacity) {
        this.cache = Collections.synchronizedMap(new LruMap<>(capacity));
    }

    /**
     * Return the parsed view of {@code uri}, parsing on demand if it is
     * not already cached. Returns empty if the URI cannot be read or
     * does not parse into at least one compilation unit.
     */
    public Optional<ParsedSource> parse(String uri) {
        if (uri == null || uri.isBlank()) return Optional.empty();
        ParsedSource cached = cache.get(uri);
        if (cached != null) return Optional.of(cached);
        ParsedSource fresh = parseFresh(uri);
        if (fresh == null) return Optional.empty();
        cache.put(uri, fresh);
        return Optional.of(fresh);
    }

    public void invalidate(String uri) {
        if (uri != null) cache.remove(uri);
    }

    public void clear() {
        cache.clear();
    }

    private static ParsedSource parseFresh(String uriStr) {
        URI uri;
        try {
            uri = URI.create(uriStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String text = read(uri);
        if (text == null) return null;

        JavacTool tool = JavacTool.create();
        StandardJavaFileManager fm = tool.getStandardFileManager(
                d -> {}, Locale.ROOT, StandardCharsets.UTF_8);
        JavaFileObject input = new InMemorySource(uri, text);
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, d -> {}, List.of(), List.of(), List.of(input));
        try {
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            CompilationUnitTree cu = firstOrNull(parsed);
            if (cu == null) return null;
            Trees trees = Trees.instance(task);
            return new ParsedSource(uri, task, cu, trees, cu.getLineMap(), trees.getSourcePositions());
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(URI uri) {
        try {
            URL url = uri.toURL();
            try (InputStream in = url.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> T firstOrNull(Iterable<? extends T> it) {
        Iterator<? extends T> i = it.iterator();
        return i.hasNext() ? i.next() : null;
    }

    private static final class LruMap<K, V> extends LinkedHashMap<K, V> {
        private final int capacity;

        LruMap(int capacity) {
            super(capacity, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
