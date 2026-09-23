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

    public ErroneousExpr(List<Node> fragments, SourceRange range) {
        super(range);
        this.fragments = fragments == null ? List.of() : List.copyOf(fragments);
        setType(JType.ERROR);
    }

    public List<Node> fragments() {
        return fragments;
    }

    @Override
    public List<? extends Node> children() {
        return kids(fragments);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitErroneousExpr(this);
    }
}
