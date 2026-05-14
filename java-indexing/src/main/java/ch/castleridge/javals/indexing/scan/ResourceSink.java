package ch.castleridge.javals.indexing.scan;

import java.io.IOException;

/**
 * Callback shape used by {@link InputSource#walk(ResourceSink)}. The walker
 * supplies a URI identifying the resource plus a {@link BytesProvider} that
 * lazily produces the content. Walkers must call {@link #accept} exactly
 * once per resource; if they throw, the scan bails out.
 */
@FunctionalInterface
public interface ResourceSink {
    void accept(String uri, String fileName, BytesProvider bytes) throws IOException;

    @FunctionalInterface
    interface BytesProvider {
        byte[] get() throws IOException;
    }
}
