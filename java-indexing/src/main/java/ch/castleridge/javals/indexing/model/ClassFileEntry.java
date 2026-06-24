package ch.castleridge.javals.indexing.model;

/**
 * Minimal index record for a {@code .class} file: enough to enumerate packages,
 * resolve classpath shadowing, and locate the real resource for bytecode reading.
 */
public record ClassFileEntry(
        String resourceUri,
        String sourceUri,
        String jvmOwnerName) {

    public String jvmName() {
        return jvmOwnerName;
    }

    public String packageJvm() {
        int slash = jvmOwnerName.lastIndexOf('/');
        return slash < 0 ? "" : jvmOwnerName.substring(0, slash);
    }
}
