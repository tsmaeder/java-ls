package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * JRT image entries. Specify {@link #ALL} for every module or a concrete
 * module name such as {@code "java.base"}.
 *
 * <p>If {@code javaHome} is {@code null}, the running JVM's {@code jrt:/}
 * filesystem is walked. Otherwise a fresh {@code jrt:/} filesystem is
 * opened with a {@code java.home} override so arbitrary JDK installs can
 * be indexed.
 *
 * <p>Every URI produced by this input - both the {@link #sourceUri()}
 * stamped on every emitted entry and the per-resource URIs emitted by
 * the walker - has the shape
 * {@code jrt:///<absolute-java-home>?<path-within-jrt-fs>}. Embedding
 * the JDK install path means entries from different JDKs never collide
 * on the same key, and any consumer holding a resource URI can recover
 * the originating install without consulting auxiliary metadata.
 */
public final class JrtInput implements InputSource {
    public final Path javaHome;

    public JrtInput(Path javaHome) {
        this.javaHome = javaHome;
    }

    @Override
    public void walk(ResourceSink sink) {
        JrtWalker.walk(this, sink);
    }

    @Override
    public String sourceUri() {
        return "jrt://" + this.javaHome.toUri().getRawPath();
    }

}
