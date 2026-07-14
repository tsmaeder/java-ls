package ch.castleridge.javals.indexing.model;

/**
 * Indexed type declaration produced from classfile parsing.
 */
public record ClassFileTypeEntry(
        String resourceUri,
        String sourceUri,
        String jvmOwnerName,
        int modifiers,
        Type superRef,
        Type[] interfaceRefs,
        TypeParamRef[] typeParams,
        FieldEntry[] fields,
        MethodEntry[] methods,
        String[] innerTypeJvmNames,
        TypeRef[] permittedSubclasses,
        RecordComponentEntry[] recordComponents,
        AnnotationRef[] annotations) implements TypeEntry {

    public ClassFileTypeEntry {
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
    public ClassFileTypeEntry(
            String resourceUri,
            String sourceUri,
            String jvmOwnerName,
            int modifiers,
            Type superRef,
            Type[] interfaceRefs,
            TypeParamRef[] typeParams,
            FieldEntry[] fields,
            MethodEntry[] methods,
            String[] innerTypeJvmNames,
            TypeRef[] permittedSubclasses,
            AnnotationRef[] annotations) {
        this(resourceUri, sourceUri, jvmOwnerName, modifiers, superRef, interfaceRefs, typeParams,
                fields, methods, innerTypeJvmNames, permittedSubclasses, EmptyArrays.RECORD_COMPONENT, annotations);
    }

    /** Backward-compatible constructor without {@link #permittedSubclasses()}. */
    public ClassFileTypeEntry(
            String resourceUri,
            String sourceUri,
            String jvmOwnerName,
            int modifiers,
            Type superRef,
            Type[] interfaceRefs,
            TypeParamRef[] typeParams,
            FieldEntry[] fields,
            MethodEntry[] methods,
            String[] innerTypeJvmNames,
            AnnotationRef[] annotations) {
        this(resourceUri, sourceUri, jvmOwnerName, modifiers, superRef, interfaceRefs, typeParams,
                fields, methods, innerTypeJvmNames, EmptyArrays.TYPE_REF, EmptyArrays.RECORD_COMPONENT, annotations);
    }
}
