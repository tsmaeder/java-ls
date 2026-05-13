package ch.castleridge.javals.indexing.index;

import java.net.URI;

public final class InMemorySource extends AbstractJavaFileObject {
    private final CharSequence text;

    public InMemorySource(URI uri, CharSequence text) {
        super(uri, Kind.SOURCE);
        this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
    }
}
