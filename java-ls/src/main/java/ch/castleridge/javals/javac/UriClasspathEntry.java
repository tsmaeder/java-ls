package ch.castleridge.javals.javac;

import java.net.URI;
import java.util.Objects;

/**
 * A {@link ClasspathEntry} that matches by URI prefix: {@link #contains}
 * returns {@code true} when this entry's {@link #sourceUri()} is a
 * prefix of the queried URI string.
 *
 * <p>This is the natural shape for entries that come from a directory
 * root or a jar file: the indexer stamps every
 * {@link ch.castleridge.javals.indexing.model.TypeEntry} it emits with
 * the {@link ch.castleridge.javals.indexing.scan.InputSource#sourceUri()}
 * of the source it came from, so an entry whose stored URI is that
 * source URI will recognise (via prefix match) every type the indexer
 * emitted for that source.
 */
public record UriClasspathEntry(String sourceUri) implements ClasspathEntry {

    public UriClasspathEntry {
        Objects.requireNonNull(sourceUri, "sourceUri");
    }

    @Override
    public boolean contains(String uri) {
        return uri != null && uri.startsWith(sourceUri);
    }

    public static UriClasspathEntry of(String uri) {
        Objects.requireNonNull(uri, "uri");
        return new UriClasspathEntry(uri);
    }
}
