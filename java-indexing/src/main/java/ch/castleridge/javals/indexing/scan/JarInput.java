package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.nio.file.Path;

/** A single {@code .jar} file. */
public record JarInput(Path jar) implements InputSource {
    @Override
    public void walk(ResourceSink sink) {
        JarWalker.walk(this, sink);
    }

    @Override
    public URI sourceUri() {
        // Use the underlying file URI (not the jar: wrapper): the resource
        // URIs emitted for entries inside the jar start with
        // jar:<file-uri>!/..., so the file URI is the cleanest prefix-free
        // key for a jar as a classpath entry.
        return jar.toUri();
    }
}
