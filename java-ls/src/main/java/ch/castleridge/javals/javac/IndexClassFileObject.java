package ch.castleridge.javals.javac;

import java.io.*;
import java.net.URI;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Synthetic {@link javax.tools.JavaFileObject} that represents an indexed
 * class. It is never backed by real bytes - the custom
 * {@link IndexClassReader} recognizes instances of this class and populates
 * javac's {@code ClassSymbol} directly from {@link #entry()}.
 *
 * <p>If {@link #openInputStream()} is ever called, something has bypassed
 * the index-aware class reader and we fail loudly on purpose.
 */
@ClientCodeWrapper.Trusted
public final class IndexClassFileObject implements JavaFileObject {

    private final TypeEntry entry;

    public IndexClassFileObject(TypeEntry entry) {
        this.entry = entry;
    }

    public TypeEntry entry() {
        return entry;
    }

    public String binaryName() {
        return entry.jvmOwnerName().replace('/', '.');
    }

    @Override
    public String getName() {
        return entry.jvmOwnerName() + ".class";
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        if (kind != Kind.CLASS) return false;
        String own = entry.jvmOwnerName();
        int slash = own.lastIndexOf('/');
        int dollar = own.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        String ownSimple = cut < 0 ? own : own.substring(cut + 1);
        return ownSimple.equals(simpleName);
    }

    @Override
    public Kind getKind() {
        return Kind.CLASS;
    }

    @Override
    public Modifier getAccessLevel() {
        return null;
    }

    @Override
    public NestingKind getNestingKind() {
        return null;
    }

        @Override
    public long getLastModified() {
        return 0L;
    }

    @Override
    public boolean delete() {
        return false;
    }

    @Override
    public Writer openWriter() {
        throw new UnsupportedOperationException("IndexClassFileObject " + entry.jvmOwnerName() + " has no bytes; IndexClassReader should fill directly from the index");
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        throw new UnsupportedOperationException("IndexClassFileObject " + entry.jvmOwnerName() + " has no bytes; IndexClassReader should fill directly from the index");
    }

    @Override
    public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
        throw new UnsupportedOperationException("IndexClassFileObject " + entry.jvmOwnerName() + " has no bytes; IndexClassReader should fill directly from the index");
    }

    @Override
    public InputStream openInputStream() {
        throw new UnsupportedOperationException("IndexClassFileObject " + entry.jvmOwnerName() + " has no bytes; IndexClassReader should fill directly from the index");
    }

    @Override
    public OutputStream openOutputStream() {
        throw new UnsupportedOperationException("IndexClassFileObject " + entry.jvmOwnerName() + " has no bytes; IndexClassReader should fill directly from the index");
    }

    @Override
    public URI toUri() {
        return URI.create(entry.resourceUri());
    }
}
