/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

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
        int modifiers,
        String name,
        Type returnType,
        ParameterEntry[] parameters,
        Type[] throwsTypes,
        TypeParamRef[] typeParams,
        boolean varargs,
        boolean hasBody,
        AnnotationValue annotationDefault,
        AnnotationRef[] annotations) implements IndexEntry {

    public MethodEntry {
        parameters = EmptyArrays.orEmpty(parameters, EmptyArrays.PARAMETER);
        throwsTypes = EmptyArrays.orEmpty(throwsTypes, EmptyArrays.TYPE);
        typeParams = EmptyArrays.orEmpty(typeParams, EmptyArrays.TYPE_PARAM);
        annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION_REF);
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
    public Type[] paramTypes() {
        if (parameters.length == 0) {
            return EmptyArrays.TYPE;
        }
        Type[] out = new Type[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            out[i] = parameters[i].type();
        }
        return out;
    }

    /** Backward-compatible constructor without method type parameters. */
    public MethodEntry(
            int modifiers,
            String name,
            Type returnType,
            Type[] paramTypes,
            Type[] throwsTypes,
            AnnotationRef[] annotations) {
        this(modifiers, name, returnType,
                paramTypesOf(paramTypes), throwsTypes, EmptyArrays.TYPE_PARAM, false, true, null, annotations);
    }

    /** Backward-compatible constructor without varargs/hasBody. */
    public MethodEntry(
            int modifiers,
            String name,
            Type returnType,
            Type[] paramTypes,
            Type[] throwsTypes,
            TypeParamRef[] typeParams,
            AnnotationRef[] annotations) {
        this(modifiers, name, returnType,
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
            int modifiers,
            String name,
            Type returnType,
            Type[] paramTypes,
            Type[] throwsTypes,
            TypeParamRef[] typeParams,
            boolean varargs,
            boolean hasBody,
            AnnotationValue annotationDefault,
            AnnotationRef[] annotations) {
        return new MethodEntry(modifiers, name, returnType,
                paramTypesOf(paramTypes), throwsTypes, typeParams, varargs, hasBody,
                annotationDefault, annotations);
    }

    private static ParameterEntry[] paramTypesOf(Type[] types) {
        if (types == null || types.length == 0) {
            return EmptyArrays.PARAMETER;
        }
        ParameterEntry[] out = new ParameterEntry[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = new ParameterEntry(null, 0, types[i], EmptyArrays.ANNOTATION_REF);
        }
        return out;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.METHOD;
    }
}
