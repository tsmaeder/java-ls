package ch.castleridge.javals.javac;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;

abstract class AbstractJavaFileObject implements JavaFileObject {
    private final URI uri;
    private final Kind kind;

    protected AbstractJavaFileObject(URI uri, Kind kind) {
        this.uri = uri;
        this.kind = kind;
    }

    @Override
    public URI toUri() {
        return uri;
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public String getName() {
        String path = uri.getPath();
        return path == null ? uri.toString() : path;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind requiredKind) {
        if (requiredKind != getKind()) return false;
        String baseName = simpleName + requiredKind.extension;
        String name = getName();
        return name.equals(baseName)
                || name.endsWith("/" + baseName)
                || name.endsWith("\\" + baseName);
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

    @Override
    public Modifier getAccessLevel() {
        return null;
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
        throw unsupported("openReader");
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        throw unsupported("getCharContent");
    }

    @Override
    public InputStream openInputStream() throws IOException {
        throw unsupported("openInputStream");
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        throw unsupported("openOutputStream");
    }

    @Override
    public Writer openWriter() throws IOException {
        throw unsupported("openWriter");
    }

    @Override
    public long getLastModified() {
        return 0L;
    }

    @Override
    public boolean delete() {
        return false;
    }

    protected UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(operation + " not supported for " + getName());
    }
}
