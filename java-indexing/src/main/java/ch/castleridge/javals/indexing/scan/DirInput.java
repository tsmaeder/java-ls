package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.nio.file.Path;

/** Directory on the local filesystem, walked recursively. */
public record DirInput(Path root) implements InputSource {
    @Override
    public void walk(ResourceSink sink) {
        DirWalker.walk(this, sink);
    }

    @Override
    public URI sourceUri() {
        return root.toUri();
    }
}
