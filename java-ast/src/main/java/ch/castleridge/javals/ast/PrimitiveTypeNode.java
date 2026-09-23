/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class PrimitiveTypeNode extends TypeNode {

    private final JType.Primitive kind;

    public PrimitiveTypeNode(JType.Primitive kind, SourceRange range) {
        super(range);
        this.kind = kind == null ? JType.Primitive.INT : kind;
        setResolvedType(this.kind);
    }

    public JType.Primitive kind() {
        return kind;
    }

    @Override
    public List<? extends Node> children() {
        return List.of();
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitPrimitiveTypeNode(this);
    }
}
