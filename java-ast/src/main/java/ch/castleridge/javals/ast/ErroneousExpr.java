/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ErroneousExpr extends Expression {

    private final List<Node> fragments;

    public ErroneousExpr(List<Node> fragments, JType type, SourceRange range) {
        super(range, coalesce(type, JType.ERROR));
        this.fragments = fragments == null ? List.of() : List.copyOf(fragments);
        for (Node __c : this.fragments) __c.setParent(this);
    }

    public List<Node> fragments() {
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
