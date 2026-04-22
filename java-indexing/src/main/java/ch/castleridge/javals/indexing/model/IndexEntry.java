package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * Common superinterface for every declaration the indexer produces. Every
 * entry knows its origin resource URI (file / jar / jrt) - as the serialised
 * URI string to keep the 400k-entry index lean - the JVM binary name of the
 * enclosing class, its access flags, and any annotations attached to the
 * declaration.
 */
public sealed interface IndexEntry permits TypeEntry, FieldEntry, MethodEntry {

    /**
     * The URI of the resource this entry was indexed from, in its
     * {@link java.net.URI#toString()} form. Consumers that need a
     * {@link java.net.URI} should parse it via {@link java.net.URI#create}.
     */
    String resourceUri();

    String jvmOwnerName();

    int accessFlags();

    List<AnnotationRef> annotations();

    EntryKind kind();
}
