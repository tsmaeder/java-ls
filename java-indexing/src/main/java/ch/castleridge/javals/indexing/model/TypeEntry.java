package ch.castleridge.javals.indexing.model;

import java.net.URI;
import java.util.List;

/**
 * A single indexed type declaration (class, interface, enum, record, or
 * annotation). For indexing purposes {@code jvmOwnerName} is the JVM binary
 * name of the type itself (e.g. {@code java/util/Map$Entry}); nested types
 * are kept as separate {@code TypeEntry} instances, with a back-reference
 * through {@link #innerTypeJvmNames()} on the enclosing entry.
 *
 * <p>The supertype and implemented interfaces are captured as
 * {@link TypeRef}s so that source-derived entries can defer name
 * resolution until the class reader consults the full index. For
 * bytecode-derived entries every {@link TypeRef} is already
 * {@link TypeRef.Resolved}.
 *
 * <p>{@link #hints()} is non-{@code null} only for source-derived
 * entries; bytecode entries leave it {@code null}.
 *
 * <p>{@link #sourceUri()} identifies the {@link
 * ch.castleridge.javals.indexing.scan.InputSource} this entry originates
 * from. It exists to let consumers (notably the file manager) reconcile
 * duplicates by classpath priority without the index itself having to
 * know about classpath order.
 */
public record TypeEntry(
        URI resourceUri,
        URI sourceUri,
        String jvmOwnerName,
        int accessFlags,
        String signatureOrNull,
        TypeRef superRef,
        List<TypeRef> interfaceRefs,
        List<FieldEntry> fields,
        List<MethodEntry> methods,
        List<String> innerTypeJvmNames,
        List<AnnotationRef> annotations,
        SourceResolutionHints hints) implements IndexEntry {

    public TypeEntry {
        interfaceRefs = interfaceRefs == null ? List.of() : List.copyOf(interfaceRefs);
        fields = fields == null ? List.of() : List.copyOf(fields);
        methods = methods == null ? List.of() : List.copyOf(methods);
        innerTypeJvmNames = innerTypeJvmNames == null ? List.of() : List.copyOf(innerTypeJvmNames);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    public String jvmName() {
        return jvmOwnerName;
    }

    public String packageJvm() {
        int slash = jvmOwnerName.lastIndexOf('/');
        return slash < 0 ? "" : jvmOwnerName.substring(0, slash);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.TYPE;
    }
}
