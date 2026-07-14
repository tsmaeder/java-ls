package ch.castleridge.javals.indexing.model;

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
        Type[] interfaceRefs,
        TypeParamRef[] typeParams,
        FieldEntry[] fields,
        MethodEntry[] methods,
        String[] innerTypeJvmNames,
        TypeRef[] permittedSubclasses,
        RecordComponentEntry[] recordComponents,
        AnnotationRef[] annotations,
        SourceResolutionHints hints) implements TypeEntry {

    public SourceTypeEntry {
        interfaceRefs = EmptyArrays.copyOrEmpty(interfaceRefs, EmptyArrays.TYPE);
        typeParams = EmptyArrays.copyOrEmpty(typeParams, EmptyArrays.TYPE_PARAM);
        fields = EmptyArrays.copyOrEmpty(fields, EmptyArrays.FIELD);
        methods = EmptyArrays.copyOrEmpty(methods, EmptyArrays.METHOD);
        innerTypeJvmNames = EmptyArrays.copyOrEmpty(innerTypeJvmNames, EmptyArrays.STRING);
        permittedSubclasses = EmptyArrays.copyOrEmpty(permittedSubclasses, EmptyArrays.TYPE_REF);
        recordComponents = EmptyArrays.copyOrEmpty(recordComponents, EmptyArrays.RECORD_COMPONENT);
        annotations = EmptyArrays.copyOrEmpty(annotations, EmptyArrays.ANNOTATION_REF);
    }

    /** Backward-compatible constructor without {@link #recordComponents()}. */
    public SourceTypeEntry(
            String resourceUri,
            String sourceUri,
            String jvmOwnerName,
            int modifiers,
            TypeDeclKind declKind,
            Type superRef,
            Type[] interfaceRefs,
            TypeParamRef[] typeParams,
            FieldEntry[] fields,
            MethodEntry[] methods,
            String[] innerTypeJvmNames,
            TypeRef[] permittedSubclasses,
            AnnotationRef[] annotations,
            SourceResolutionHints hints) {
        this(resourceUri, sourceUri, jvmOwnerName, modifiers, declKind,
                superRef, interfaceRefs, typeParams, fields, methods,
                innerTypeJvmNames, permittedSubclasses, EmptyArrays.RECORD_COMPONENT, annotations, hints);
    }
}
