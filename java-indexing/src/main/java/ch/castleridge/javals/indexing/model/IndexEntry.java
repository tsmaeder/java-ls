package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * Common superinterface for every declaration the indexer produces. Every
 * entry knows its origin resource URI (file / jar / jrt) - as the serialised
 * URI string to keep the 400k-entry index lean - the JVM binary name of the
 * enclosing class, its stored declaration modifiers, and any annotations
 * attached to the declaration.
 *
 * <p>For source-derived type entries, {@link #modifiers()} holds only explicit
 * source modifiers; JVM classfile access flags are synthesized later by
 * {@code IndexClassReader}. For bytecode-derived entries, {@link #modifiers()}
 * is the ASM access mask and is used as-is at read time.
 */
public sealed interface IndexEntry permits TypeEntry, FieldEntry, MethodEntry {

    /**
     * The URI of the resource this entry was indexed from, in its
     * {@link java.net.URI#toString()} form. Consumers that need a
     * {@link java.net.URI} should parse it via {@link java.net.URI#create}.
     */
    String resourceUri();

    String jvmOwnerName();

    int modifiers();

    List<AnnotationRef> annotations();

    EntryKind kind();

}
