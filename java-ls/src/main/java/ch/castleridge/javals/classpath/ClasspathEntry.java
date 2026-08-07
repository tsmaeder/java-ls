package ch.castleridge.javals.classpath;

/**
 * A single entry on a {@link ClasspathOrder}. Decides for itself whether
 * a given source URI (as stamped on
 * {@link ch.castleridge.javals.indexing.model.TypeEntry#sourceUri()}) is
 * "owned" by this entry.
 *
 * <p>Sealed so adding new entry shapes is an explicit decision; today
 * the only implementation is {@link UriClasspathEntry}.
 */
public sealed interface ClasspathEntry permits UriClasspathEntry {

    /** True if this entry owns {@code sourceUri}. */
    boolean contains(String sourceUri);
}
