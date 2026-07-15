package ch.castleridge.javals.indexing.scan;

import java.io.IOException;

/**
 * Callback shape used by {@link InputSource#walk(ResourceSink, boolean)}. The
 * walker supplies a path relative to the input source plus a
 * {@link BytesProvider} that lazily produces the content. Walkers must call
 * {@link #accept} exactly once per resource; if they throw, the scan bails out.
 *
 * <p>The full resource URI is not built during the walk; the scanner pairs
 * {@code relativePath} with {@link InputSource#sourceUri()} and resolves a
 * URI only when a consumer needs one (via
 * {@link ch.castleridge.javals.indexing.model.ResourceUris#resolve}).
 *
 * <p>{@code bytes} may be {@code null} in catalog-only mode for ordinary
 * {@code .class} files: the scanner records a {@link
 * ch.castleridge.javals.indexing.model.ClassFileEntry} from the relative path
 * alone and does not read bytecode. {@code module-info.class} and
 * {@code .java} sources still require a non-null provider.
 */
@FunctionalInterface
public interface ResourceSink {
    void accept(String relativePath, String fileName, BytesProvider bytes) throws IOException;

    @FunctionalInterface
    interface BytesProvider {
        byte[] get() throws IOException;
    }
}
