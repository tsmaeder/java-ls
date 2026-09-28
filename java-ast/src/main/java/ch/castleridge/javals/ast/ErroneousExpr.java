/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ErroneousExpr extends Expression {

    private final Node[] fragments;

    public ErroneousExpr(Node[] fragments, JType type, SourceRange range) {
        super(range, coalesce(type, JType.ERROR));
        this.fragments = EmptyArrays.orEmpty(fragments, EmptyArrays.NODE);
        for (Node __c : this.fragments) __c.setParent(this);
    }

    public Node[] fragments() {
        return fragments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : fragments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitErroneousExpr(this);
    }
}
