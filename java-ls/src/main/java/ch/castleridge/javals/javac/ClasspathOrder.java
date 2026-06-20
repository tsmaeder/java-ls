package ch.castleridge.javals.javac;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Ordered list of {@link ClasspathEntry} expressing classpath priority
 * for a compile session. Entries earlier in the list shadow later ones
 * when more than one entry claims the same JVM binary name.
 *
 * <p>The index (built by the scanner) deliberately knows nothing about
 * classpath order: it keeps every {@link TypeEntry} it ever saw, tagged
 * with its originating {@link TypeEntry#sourceUri()}. This class - held
 * by the file manager and class reader - is what converts that tag into
 * a deterministic winner for a given compilation, by asking each
 * {@link ClasspathEntry} in order whether it
 * {@link ClasspathEntry#contains(String) contains} the source URI.
 */
public final class ClasspathOrder {

    /**
     * A classpath order that admits every {@link TypeEntry} regardless of
     * its source. When multiple candidates exist the first one encountered
     * wins; callers that need determinism should build an explicit order.
     */
    public static final ClasspathOrder UNRESTRICTED = new ClasspathOrder(List.of(), true);

    private final List<ClasspathEntry> entries;
    private final boolean unrestricted;
    private final ConcurrentMap<String, Integer> rankCache = new ConcurrentHashMap<>();

    public ClasspathOrder(List<ClasspathEntry> entries, boolean unrestricted) {
        this.entries = entries;
        this.unrestricted = unrestricted;
    }

    /**
     * Classpath priority of {@code sourceUri}: index of the earliest
     * {@link ClasspathEntry} whose prefix matches, or {@code -1} when none
     * claim it. Under {@linkplain #UNRESTRICTED unrestricted} order every
     * non-null URI ranks {@code 0}.
     */
    public int rank(String sourceUri) {
        if (unrestricted) return sourceUri == null ? -1 : 0;
        if (sourceUri == null) return -1;
        return rankCache.computeIfAbsent(sourceUri, this::computeRank);
    }

    private int computeRank(String sourceUri) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(sourceUri)) {
                return i;
            }
        }
        return -1;
    }

    /** True if any entry on this classpath claims {@code sourceUri}. */
    public boolean contains(String sourceUri) {
        return unrestricted || rank(sourceUri) >= 0;
    }

    /**
     * Pick the winning {@link T} from a set of candidates. The one with the
     * lowest index in the classpath order is returned. When this order is
     * {@linkplain #UNRESTRICTED unrestricted}, the first candidate is
     * returned if no entry-specific match exists.
     */
    public <T> T pick(Collection<T> candidates, Function<T, String> uriMapper) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (unrestricted) {
            return candidates.iterator().next();
        }
        T best = null;
        int bestRank = Integer.MAX_VALUE;
        for (T c : candidates) {
            int r = rank(uriMapper.apply(c));
            if (r >= 0 && r < bestRank) {
                bestRank = r;
                best = c;
            }
        }
        return best;
    }
}
