/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ArrayAccessExpr extends Expression {

    private final Expression array;
    private final Expression index;

    public ArrayAccessExpr(Expression array, Expression index, JType type, SourceRange range) {
        super(range, coalesce(type,
                array != null && array.type() instanceof JType.Array arr ? arr.element() : null));
        this.array = array;
        this.index = index;
        if (array != null) array.setParent(this);
        if (index != null) index.setParent(this);
    }

    public Expression array() {
        return array;
    }

    public Expression index() {
        return index;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, array, offset);
        deepest = deeper(deepest, index, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayAccessExpr(this);
    }
}
