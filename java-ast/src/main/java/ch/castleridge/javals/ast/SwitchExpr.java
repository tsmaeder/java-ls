/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class SwitchExpr extends Expression {

    private final Expression selector;
    private final List<SwitchArm> arms;

    public SwitchExpr(Expression selector, List<SwitchArm> arms, SourceRange range) {
        super(range);
        this.selector = selector;
        this.arms = arms == null ? List.of() : List.copyOf(arms);
    }

    public Expression selector() {
        return selector;
    }

    public List<SwitchArm> arms() {
        return arms;
    }

    @Override
    public List<? extends Node> children() {
        return kids(selector, arms);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitSwitchExpr(this);
    }
}
