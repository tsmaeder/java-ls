package ch.castleridge.javals.indexing.scan;

import java.net.URI;

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
 */
public sealed interface InputSource permits DirInput, JarInput, JrtInput {

    /**
     * @param catalogClassFilesOnly when {@code true}, ordinary {@code .class}
     *        files are catalogued from their entry path only; bytecode is not
     *        read. {@code module-info.class} and {@code .java} files are
     *        still read.
     */
    void walk(ResourceSink sink, boolean catalogClassFilesOnly);

    /**
     * URI identifying this source as a whole (the jar file, the directory
     * root, the JRT module subset). Stable across runs so callers can build
     * classpath-priority maps keyed by it.
     */
    String sourceUri();
}
