package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.nio.file.Path;

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
    private URI sourceUri;

    public JrtInput(Path javaHome) {
        this.javaHome = javaHome;
        this.sourceUri = URI.create("jrt://" + javaHomeUriPath(javaHome));
    }

    @Override
    public void walk(ResourceSink sink) {
        JrtWalker.walk(this, sink);
    }

    @Override
    public URI sourceUri() {
        return sourceUri;
    }

    /**
     * Convert {@code javaHome} to the URI-path fragment used as the
     * authority/path part of our {@code jrt://} URIs (e.g.
     * {@code /C:/Java/jdk-21} on Windows, {@code /usr/lib/jvm/java-21}
     * on Linux). Trailing slashes are stripped so the same install
     * always produces the same URI prefix.
     */
    static String javaHomeUriPath(Path javaHome) {
        String p = javaHome.toUri().getRawPath();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
