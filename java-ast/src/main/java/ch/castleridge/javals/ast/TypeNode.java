/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class TypeNode extends Node
        permits PrimitiveTypeNode, VoidTypeNode, ArrayTypeNode, ParameterizedTypeNode, TypeName,
                WildcardTypeNode, UnionTypeNode, IntersectionTypeNode, VarTypeNode,
                AnnotatedTypeNode, ErroneousType {

    private final JType resolved;

    protected TypeNode(SourceRange range, JType resolved) {
        super(range);
        this.resolved = resolved == null ? JType.ERROR : resolved;
    }

    public final JType resolvedType() {
        return resolved;
    }

    /** Prefer {@code resolved} when non-null and not ERROR; otherwise {@code fallback}. */
    protected static JType coalesce(JType resolved, JType fallback) {
        if (resolved != null && resolved != JType.ERROR) return resolved;
        return fallback == null ? JType.ERROR : fallback;
    }
}
