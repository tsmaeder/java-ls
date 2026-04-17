package ch.castleridge.javals.indexing.scan;

import java.net.URI;

/**
 * JRT image entries. Specify {@link #ALL} for every module or a concrete
 * module name such as {@code "java.base"}.
 */
public record JrtInput(String moduleOrAll) implements InputSource {
    public static final String ALL = "*";

    @Override
    public void walk(ResourceSink sink) {
        JrtWalker.walk(this, sink);
    }

    @Override
    public URI sourceUri() {
        if (ALL.equals(moduleOrAll)) {
            return URI.create("jrt:/");
        }
        return URI.create("jrt:/" + moduleOrAll + "/");
    }
}
