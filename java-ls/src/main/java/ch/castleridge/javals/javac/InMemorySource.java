package ch.castleridge.javals.javac;

import java.net.URI;

final class InMemorySource extends AbstractJavaFileObject {
    private final CharSequence text;

    InMemorySource(URI uri, CharSequence text) {
        super(uri, Kind.SOURCE);
        this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
    }
}
