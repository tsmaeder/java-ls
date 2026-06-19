package ch.castleridge.javals.javac;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.InputSource;

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

    public ClasspathOrder(List<ClasspathEntry> entries, boolean unrestricted) {
        this.entries = entries;
        this.unrestricted = unrestricted;
    }

    /** True if any entry on this classpath claims {@code sourceUri}. */
    public boolean contains(String sourceUri) {
        if (unrestricted) return true;
       return entries.stream().anyMatch(e -> e.contains(sourceUri));
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
        for (ClasspathEntry e : entries) {
            for (T c : candidates) {
                if (e.contains(uriMapper.apply(c))) {
                    return c;
                }
            }
        }
        if (unrestricted) {
            return candidates.iterator().next();
        }
        return null;
    }
}
