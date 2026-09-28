/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class InstanceOfExpr extends Expression {

    private final Expression expression;
    private final TypeNode typeNode;
    private final Pattern pattern;

    public InstanceOfExpr(Expression expression,
                          TypeNode typeNode,
                          Pattern pattern,
                          JType type,
                          SourceRange range) {
        super(range, coalesce(type, JType.Primitive.BOOLEAN));
        this.expression = expression;
        this.typeNode = typeNode;
        this.pattern = pattern;
        if (expression != null) expression.setParent(this);
        if (typeNode != null) typeNode.setParent(this);
        if (pattern != null) pattern.setParent(this);
    }

    public Expression expression() {
        return expression;
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public Pattern pattern() {
        return pattern;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, expression, offset);
        deepest = deeper(deepest, typeNode, offset);
        deepest = deeper(deepest, pattern, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitInstanceOfExpr(this);
    }
}
