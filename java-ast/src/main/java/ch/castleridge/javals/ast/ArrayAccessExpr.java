/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ArrayAccessExpr extends Expression {

    private final Expression array;
    private final Expression index;

    public ArrayAccessExpr(Expression array, Expression index, SourceRange range) {
        super(range);
        this.array = array;
        this.index = index;
        if (array != null && array.type() instanceof JType.Array arr) setType(arr.element());
    }

    public Expression array() {
        return array;
    }

    public Expression index() {
        return index;
    }

    @Override
    public List<? extends Node> children() {
        return kids(array, index);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayAccessExpr(this);
    }
}
