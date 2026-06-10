package ch.castleridge.javals.indexing.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single method (or constructor) declaration attached to a
 * {@link TypeEntry}. Return type, parameter types and thrown types are
 * {@link Type}s; for source-derived methods they may include
 * {@link TypeRef.Unresolved} leaves that the class reader resolves later
 * using the enclosing type's {@link SourceResolutionHints}.
 *
 * <p>{@link #varargs()} and {@link #hasBody()} are meaningful for
 * source-derived methods; bytecode entries use defaults and rely on
 * {@link #modifiers()} from ASM.
 *
 * <p>{@link #annotationDefault()} is non-null when this method is an
 * annotation element with a default value (either an
 * {@code AnnotationDefault} attribute in bytecode or a {@code default}
 * clause in source). The actual default value is preserved so that
 * {@code IndexClassReader} can construct an accurate
 * {@code MethodSymbol.defaultValue} attribute; for source-derived
 * defaults the indexer falls back to {@link AnnotationValue.Unsupported}
 * when the default expression cannot be evaluated without a symbol
 * table.
 */
public record MethodEntry(
        String resourceUri,
        String jvmOwnerName,
        int modifiers,
        String name,
        Type returnType,
        List<ParameterEntry> parameters,
        List<Type> throwsTypes,
        List<TypeParamRef> typeParams,
        boolean varargs,
        boolean hasBody,
        AnnotationValue annotationDefault,
        List<AnnotationRef> annotations) implements IndexEntry {

    public MethodEntry {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        throwsTypes = throwsTypes == null ? List.of() : List.copyOf(throwsTypes);
        typeParams = typeParams == null ? List.of() : List.copyOf(typeParams);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /**
     * {@code true} when this method has a default value attached. Kept
     * for backward compatibility with the previous boolean field; new
     * code should consult {@link #annotationDefault()} directly to
     * recover the actual value.
     */
    public boolean hasAnnotationDefault() {
        return annotationDefault != null;
    }

    /**
     * Derived projection: the parameter types in declaration order, for
     * callers that only need {@link Type}s and don't care about names,
     * modifiers or per-parameter annotations.
     */
    public List<Type> paramTypes() {
        if (parameters.isEmpty()) return List.of();
        List<Type> out = new ArrayList<>(parameters.size());
        for (ParameterEntry p : parameters) out.add(p.type());
        return List.copyOf(out);
    }

    /** Backward-compatible constructor without method type parameters. */
    public MethodEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            Type returnType,
            List<Type> paramTypes,
            List<Type> throwsTypes,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypesOf(paramTypes), throwsTypes, List.of(), false, true, null, annotations);
    }

    /** Backward-compatible constructor without varargs/hasBody. */
    public MethodEntry(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            Type returnType,
            List<Type> paramTypes,
            List<Type> throwsTypes,
            List<TypeParamRef> typeParams,
            List<AnnotationRef> annotations) {
        this(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypesOf(paramTypes), throwsTypes, typeParams, false, true, null, annotations);
    }

    /**
     * Factory for callers that only know about parameter types (no
     * names, modifiers or per-parameter annotations). Each
     * {@link Type} is wrapped in an empty {@link ParameterEntry}.
     * Used by tests and any consumer that doesn't have a richer source
     * of parameter data; richer producers (the bytecode and source
     * indexers) build proper {@link ParameterEntry}s and call the
     * canonical constructor directly.
     */
    public static MethodEntry ofTypes(
            String resourceUri,
            String jvmOwnerName,
            int modifiers,
            String name,
            Type returnType,
            List<Type> paramTypes,
            List<Type> throwsTypes,
            List<TypeParamRef> typeParams,
            boolean varargs,
            boolean hasBody,
            AnnotationValue annotationDefault,
            List<AnnotationRef> annotations) {
        return new MethodEntry(resourceUri, jvmOwnerName, modifiers, name, returnType,
                paramTypesOf(paramTypes), throwsTypes, typeParams, varargs, hasBody,
                annotationDefault, annotations);
    }

    private static List<ParameterEntry> paramTypesOf(List<Type> types) {
        if (types == null || types.isEmpty()) return List.of();
        List<ParameterEntry> out = new ArrayList<>(types.size());
        for (Type t : types) {
            out.add(new ParameterEntry(null, 0, t, List.of()));
        }
        return List.copyOf(out);
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
