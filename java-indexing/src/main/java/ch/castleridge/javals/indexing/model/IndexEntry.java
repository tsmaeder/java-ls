package ch.castleridge.javals.indexing.model;

import java.net.URI;
import java.util.List;

/**
 * Common superinterface for every declaration the indexer produces. Every
 * entry knows its origin resource URI (file / jar / jrt), the JVM binary name
 * of the enclosing class, its access flags, and any annotations attached to
 * the declaration.
 */
public sealed interface IndexEntry permits TypeEntry, FieldEntry, MethodEntry {

    URI resourceUri();

    String jvmOwnerName();

    int accessFlags();

    List<AnnotationRef> annotations();

    EntryKind kind();
}
