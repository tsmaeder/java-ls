package ch.castleridge.javals.indexing.scan;

/**
 * Something the scanner can pull declarations from. Three shapes:
 * a filesystem tree, a jar file, or a subset of the JRT image.
 *
 * <p>Every input source exposes a {@link #sourceUri()} that uniquely
 * identifies it. That URI is stamped on every {@link
 * ch.castleridge.javals.indexing.model.TypeEntry} the scanner emits from
 * this source so downstream consumers (e.g. the file manager) can later
 * decide, given a list of input sources ordered by classpath priority,
 * which duplicate entry to prefer.
 *
 * <p>Walkers emit paths relative to this source (jar entry names, paths under
 * a directory root, paths inside the jrt filesystem). Full resource URIs are
 * resolved later via {@link ch.castleridge.javals.indexing.model.ResourceUris}.
 */
public sealed interface InputSource permits DirInput, JarInput, JrtInput {

    /**
     * @param indexClassFiles when {@code true}, ordinary {@code .class}
     *        files are read and fully indexed; when {@code false}, they are
     *        catalogued from their relative path only. {@code module-info.class}
     *        and {@code .java} files are still read.
     */
    void walk(ResourceSink sink, boolean indexClassFiles);

    /**
     * URI identifying this source as a whole (the jar file, the directory
     * root, the JRT module subset). Stable across runs so callers can build
     * classpath-priority maps keyed by it.
     */
    String sourceUri();
}
