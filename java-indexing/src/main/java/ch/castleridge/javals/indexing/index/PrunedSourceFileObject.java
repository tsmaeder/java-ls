package ch.castleridge.javals.indexing.index;

import java.net.URI;

import ch.castleridge.javals.indexing.model.PrunedSourceEntry;

/**
 * {@link javax.tools.JavaFileObject} backed by pruned API-stub source text
 * from the index. Served on {@code SOURCE_PATH} so javac parses and
 * attributes the stub instead of synthesizing symbols from {@link
 * ch.castleridge.javals.indexing.model.TypeEntry}.
 */
public final class PrunedSourceFileObject extends AbstractJavaFileObject {

    private final PrunedSourceEntry entry;

    public PrunedSourceFileObject(PrunedSourceEntry entry) {
        super(URI.create(entry.resourceUri()), Kind.SOURCE);
        this.entry = entry;
    }

    public static PrunedSourceFileObject from(PrunedSourceEntry entry) {
        return new PrunedSourceFileObject(entry);
    }

    public PrunedSourceEntry entry() {
        return entry;
    }

    public String sourceUri() {
        return entry.sourceUri();
    }

    public String binaryName() {
        return entry.primaryBinaryName().replace('/', '.');
    }

    public String jvmOwnerName() {
        return entry.primaryBinaryName();
    }

    @Override
    public String getName() {
        return entry.relativeName();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return entry.prunedText();
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        if (kind != Kind.SOURCE) return false;
        String own = entry.primaryBinaryName();
        int slash = own.lastIndexOf('/');
        int dollar = own.lastIndexOf('$');
        int cut = Math.max(slash, dollar);
        String ownSimple = cut < 0 ? own : own.substring(cut + 1);
        return ownSimple.equals(simpleName);
    }
}
