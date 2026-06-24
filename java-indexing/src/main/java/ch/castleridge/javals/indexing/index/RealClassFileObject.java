package ch.castleridge.javals.indexing.index;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import javax.tools.JavaFileObject;

import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ModuleFileEntry;

/**
 * {@link JavaFileObject} backed by real {@code .class} bytes from a jar,
 * jrt image, or directory. Used in minimal class-file indexing mode so javac
 * reads bytecode normally instead of synthesizing symbols from {@link
 * ch.castleridge.javals.indexing.model.TypeEntry}.
 */
public final class RealClassFileObject extends AbstractJavaFileObject {

    private final String sourceUri;
    private final String jvmOwnerName;

    public RealClassFileObject(URI resourceUri, String sourceUri, String jvmOwnerName) {
        super(resourceUri, Kind.CLASS);
        this.sourceUri = sourceUri;
        this.jvmOwnerName = jvmOwnerName;
    }

    public static RealClassFileObject from(ClassFileEntry entry) {
        return new RealClassFileObject(
                URI.create(entry.resourceUri()), entry.sourceUri(), entry.jvmOwnerName());
    }

    public static RealClassFileObject moduleInfo(ModuleFileEntry entry) {
        return new RealClassFileObject(
                URI.create(entry.resourceUri()), entry.sourceUri(), "module-info");
    }

    public String sourceUri() {
        return sourceUri;
    }

    public String jvmOwnerName() {
        return jvmOwnerName;
    }

    public String binaryName() {
        return jvmOwnerName.replace('/', '.');
    }

    @Override
    public String getName() {
        return jvmOwnerName + ".class";
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        if (kind != Kind.CLASS) return false;
        if ("module-info".equals(simpleName)) {
            return "module-info".equals(jvmOwnerName);
        }
        int slash = jvmOwnerName.lastIndexOf('/');
        int dollar = jvmOwnerName.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        String ownSimple = cut < 0 ? jvmOwnerName : jvmOwnerName.substring(cut + 1);
        return ownSimple.equals(simpleName);
    }

    @Override
    public InputStream openInputStream() throws IOException {
        String scheme = toUri().getScheme();
        if ("jrt".equals(scheme)) {
            return JrtResourceReader.openStream(toUri().toString());
        }
        try (InputStream in = toUri().toURL().openStream()) {
            return new java.io.ByteArrayInputStream(in.readAllBytes());
        }
    }
}
