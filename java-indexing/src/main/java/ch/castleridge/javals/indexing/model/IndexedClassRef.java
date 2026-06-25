package ch.castleridge.javals.indexing.model;

/**
 * Locator metadata shared by full {@link TypeEntry} and minimal
 * {@link ClassFileEntry} records: enough to map a compiled class back to
 * its originating resource and sources-jar companion.
 */
public record IndexedClassRef(
        String resourceUri,
        String sourceUri,
        String jvmOwnerName) {

    public String jvmName() {
        return jvmOwnerName;
    }

    public static IndexedClassRef from(TypeEntry entry) {
        return new IndexedClassRef(entry.resourceUri(), entry.sourceUri(), entry.jvmOwnerName());
    }

    public static IndexedClassRef from(ClassFileEntry entry) {
        return new IndexedClassRef(entry.resourceUri(), entry.sourceUri(), entry.jvmOwnerName());
    }

    public static IndexedClassRef from(PrunedSourceEntry entry, String jvmOwnerName) {
        return new IndexedClassRef(entry.resourceUri(), entry.sourceUri(), jvmOwnerName);
    }
}
