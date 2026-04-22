package ch.castleridge.javals.javac;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.InputSource;

/**
 * Ordered list of {@link InputSource}s expressing classpath priority for a
 * compile session. Entries earlier in the list shadow later ones when the
 * same JVM binary name occurs in more than one source.
 *
 * <p>The index (built by the scanner) deliberately knows nothing about
 * classpath order: it keeps every {@link TypeEntry} it ever saw, tagged
 * with its originating {@link TypeEntry#sourceUri()}. This class - held by
 * the file manager and class reader - is what converts that tag into a
 * deterministic winner for a given compilation.
 *
 * <p>Source URIs are matched as their string form so the class matches how
 * {@link TypeEntry#sourceUri()} is stored on the index.
 */
public final class ClasspathOrder {

    /**
     * A classpath order that admits every {@link TypeEntry} regardless of
     * its source. When multiple candidates exist the first one encountered
     * wins; callers that need determinism should build an explicit order.
     */
    public static final ClasspathOrder UNRESTRICTED = new ClasspathOrder(List.of(), true);

    private final List<String> order;
    private final Map<String, Integer> priority;
    private final boolean unrestricted;

    private ClasspathOrder(List<String> order, boolean unrestricted) {
        this.order = List.copyOf(order);
        this.unrestricted = unrestricted;
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < this.order.size(); i++) {
            map.putIfAbsent(this.order.get(i), i);
        }
        this.priority = Map.copyOf(map);
    }

    public static ClasspathOrder of(List<URI> sourceUris) {
        Objects.requireNonNull(sourceUris);
        List<String> strings = new ArrayList<>(sourceUris.size());
        for (URI u : sourceUris) strings.add(u == null ? null : u.toString());
        return new ClasspathOrder(strings, false);
    }

    public static ClasspathOrder ofStrings(List<String> sourceUris) {
        Objects.requireNonNull(sourceUris);
        return new ClasspathOrder(sourceUris, false);
    }

    public static ClasspathOrder ofSources(List<? extends InputSource> sources) {
        Objects.requireNonNull(sources);
        List<String> uris = new ArrayList<>(sources.size());
        for (InputSource s : sources) {
            URI u = s.sourceUri();
            uris.add(u == null ? null : u.toString());
        }
        return ofStrings(uris);
    }

    /** The source URIs (string form) in priority order. */
    public List<String> order() {
        return order;
    }

    /** True if {@code sourceUri} is part of this classpath. */
    public boolean contains(String sourceUri) {
        if (unrestricted) return true;
        return priority.containsKey(sourceUri);
    }

    /**
     * Priority of a source URI: 0 = highest. Sources not on the classpath
     * return {@link Integer#MAX_VALUE}. For {@link #UNRESTRICTED} every
     * URI returns 0.
     */
    public int priorityOf(String sourceUri) {
        if (unrestricted) return 0;
        Integer p = priority.get(sourceUri);
        return p == null ? Integer.MAX_VALUE : p;
    }

    /**
     * Pick the winning {@link TypeEntry} from a set of candidates with the
     * same JVM binary name: the one with the lowest classpath priority
     * whose source is on the classpath. Returns {@code null} if no
     * candidate is on the classpath.
     */
    public TypeEntry pick(Collection<TypeEntry> candidates) {
        TypeEntry best = null;
        int bestPri = Integer.MAX_VALUE;
        for (TypeEntry e : candidates) {
            if (!contains(e.sourceUri())) continue;
            int p = priorityOf(e.sourceUri());
            if (p < bestPri || best == null) {
                bestPri = p;
                best = e;
            }
        }
        return best;
    }

    /**
     * True if at least one candidate in {@code candidates} is on the
     * classpath.
     */
    public boolean anyOnClasspath(Collection<TypeEntry> candidates) {
        for (TypeEntry e : candidates) {
            if (contains(e.sourceUri())) return true;
        }
        return false;
    }
}
