/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ClassLiteralExpr extends Expression {

    private final TypeNode typeNode;

    public ClassLiteralExpr(TypeNode typeNode, JType type, SourceRange range) {
        super(range, coalesce(type, JType.Declared.of("java/lang/Class")));
        this.typeNode = typeNode;
        if (typeNode != null) typeNode.setParent(this);
    }

    public TypeNode typeNode() {
        return typeNode;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, typeNode, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitClassLiteralExpr(this);
    }
}
