package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * JRT image entries. Specify {@link #ALL} for every module or a concrete
 * module name such as {@code "java.base"}.
 *
 * <p>If {@code javaHome} is {@code null}, the running JVM's {@code jrt:/}
 * filesystem is walked. Otherwise a fresh {@code jrt:/} filesystem is
 * opened with a {@code java.home} override so arbitrary JDK installs can
 * be indexed.
 */
public record JrtInput(String moduleOrAll, Path javaHome) implements InputSource {
    public static final String ALL = "*";

    public JrtInput(String moduleOrAll) {
        this(moduleOrAll, null);
    }

    @Override
    public void walk(ResourceSink sink) {
        JrtWalker.walk(this, sink);
    }

    @Override
    public URI sourceUri() {
        String base = ALL.equals(moduleOrAll) ? "jrt:/" : "jrt:/" + moduleOrAll + "/";
        if (javaHome == null) {
            return URI.create(base);
        }
        String encoded = URLEncoder.encode(javaHome.toString(), StandardCharsets.UTF_8);
        return URI.create(base + "?java.home=" + encoded);
    }
}
