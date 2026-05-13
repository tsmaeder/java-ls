package ch.castleridge.javals.javac;

import java.io.*;
import java.net.URI;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.index.AbstractJavaFileObject;
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
public final class IndexClassFileObject extends AbstractJavaFileObject {

    private final TypeEntry entry;

    public IndexClassFileObject(TypeEntry entry) {
        super(URI.create(entry.resourceUri()), Kind.CLASS);
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
}
