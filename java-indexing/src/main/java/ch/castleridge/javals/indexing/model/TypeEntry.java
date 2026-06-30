package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * Common surface for indexed type declarations regardless of origin
 * (source parser or classfile parser). Origin-specific payload is carried
 * by {@link SourceTypeEntry} and {@link ClassFileTypeEntry}.
 */
public sealed interface TypeEntry extends IndexEntry permits SourceTypeEntry, ClassFileTypeEntry {
    String sourceUri();

    Type superRef();

    List<Type> interfaceRefs();

    List<TypeParamRef> typeParams();

    List<FieldEntry> fields();

    List<MethodEntry> methods();

    List<String> innerTypeJvmNames();

    List<TypeRef> permittedSubclasses();

    List<RecordComponentEntry> recordComponents();

    default String jvmName() {
        return jvmOwnerName();
    }

    default String packageJvm() {
        String owner = jvmOwnerName();
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    @Override
    default EntryKind kind() {
        return EntryKind.TYPE;
    }
}
