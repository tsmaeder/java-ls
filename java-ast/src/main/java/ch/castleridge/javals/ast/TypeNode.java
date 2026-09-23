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

    private JType resolved = JType.ERROR;

    protected TypeNode(SourceRange range) {
        super(range);
    }

    public final JType resolvedType() {
        return resolved;
    }

    public final void setResolvedType(JType type) {
        if (frozen()) throw new IllegalStateException("frozen");
        this.resolved = type == null ? JType.ERROR : type;
    }
}
