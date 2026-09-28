/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ArrayInitExpr extends Expression {

    private final Expression[] elements;

    public ArrayInitExpr(Expression[] elements, JType type, SourceRange range) {
        super(range, type);
        this.elements = EmptyArrays.orEmpty(elements, EmptyArrays.EXPRESSION);
        for (Node __c : this.elements) __c.setParent(this);
    }

    public Expression[] elements() {
        return elements;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : elements) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayInitExpr(this);
    }
}
