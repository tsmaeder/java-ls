/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class CastExpr extends Expression {

    private final TypeNode typeNode;
    private final Expression expression;

    public CastExpr(TypeNode typeNode, Expression expression, JType type, SourceRange range) {
        super(range, coalesce(type, typeNode != null ? typeNode.resolvedType() : null));
        this.typeNode = typeNode;
        this.expression = expression;
        if (typeNode != null) typeNode.setParent(this);
        if (expression != null) expression.setParent(this);
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public Expression expression() {
        return expression;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, typeNode, offset);
        deepest = deeper(deepest, expression, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCastExpr(this);
    }
}
