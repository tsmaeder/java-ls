package ch.castleridge.javals.indexing.model;

import java.util.List;

/**
 * Indexed type declaration produced from source parsing.
 */
public record SourceTypeEntry(
        String resourceUri,
        String sourceUri,
        String jvmOwnerName,
        int modifiers,
        TypeDeclKind declKind,
        Type superRef,
        List<Type> interfaceRefs,
        List<TypeParamRef> typeParams,
        List<FieldEntry> fields,
        List<MethodEntry> methods,
        List<String> innerTypeJvmNames,
        List<TypeRef> permittedSubclasses,
        List<RecordComponentEntry> recordComponents,
        List<AnnotationRef> annotations,
        SourceResolutionHints hints) implements TypeEntry {

    public SourceTypeEntry {
        interfaceRefs = interfaceRefs == null ? List.of() : List.copyOf(interfaceRefs);
        typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        fields = fields == null ? List.of() : List.copyOf(fields);
        methods = methods == null ? List.of() : List.copyOf(methods);
        innerTypeJvmNames = innerTypeJvmNames == null ? List.of() : List.copyOf(innerTypeJvmNames);
        permittedSubclasses = permittedSubclasses == null ? List.of() : List.copyOf(permittedSubclasses);
        recordComponents = recordComponents == null ? List.of() : List.copyOf(recordComponents);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** Backward-compatible constructor without {@link #recordComponents()}. */
    public SourceTypeEntry(
            String resourceUri,
            String sourceUri,
            String jvmOwnerName,
            int modifiers,
            TypeDeclKind declKind,
            Type superRef,
            List<Type> interfaceRefs,
            List<TypeParamRef> typeParams,
            List<FieldEntry> fields,
            List<MethodEntry> methods,
            List<String> innerTypeJvmNames,
            List<TypeRef> permittedSubclasses,
            List<AnnotationRef> annotations,
            SourceResolutionHints hints) {
        this(resourceUri, sourceUri, jvmOwnerName, modifiers, declKind,
                superRef, interfaceRefs, typeParams, fields, methods,
                innerTypeJvmNames, permittedSubclasses, List.of(), annotations, hints);
    }
}
