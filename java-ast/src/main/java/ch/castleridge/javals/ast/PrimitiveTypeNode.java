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

    public PrimitiveTypeNode(JType.Primitive kind, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, kind == null ? JType.Primitive.INT : kind));
        this.kind = kind == null ? JType.Primitive.INT : kind;
    }

    public JType.Primitive kind() {
        return kind;
    }
    @Override
    public Node nodeAt(int offset) {
        return covers(offset) ? this : null;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitPrimitiveTypeNode(this);
    }
}
