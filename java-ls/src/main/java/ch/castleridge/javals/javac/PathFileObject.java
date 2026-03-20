package ch.castleridge.javals.javac;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;

public class PathFileObject implements JavaFileObject {
    private final Path path;
    private final Kind kind;

    PathFileObject(Path path, Kind kind) {
        this.path = path;
        this.kind = kind;
    }

    @Override
    public String toString() {
        return "PathFileObject{" +
                "path=" + path +
                ", kind=" + kind +
                '}';
    }

    @Override
    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        return false;
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
    public URI toUri() {
        return null;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return null;
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return null;
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
        return null;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return null;
    }

    @Override
    public Writer openWriter() throws IOException {
        return null;
    }

    @Override
    public long getLastModified() {
        return 0;
    }

    @Override
    public boolean delete() {
        return false;
    }
}
