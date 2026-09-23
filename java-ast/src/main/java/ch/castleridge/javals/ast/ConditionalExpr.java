/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ConditionalExpr extends Expression {

    private final Expression condition;
    private final Expression thenExpr;
    private final Expression elseExpr;

    public ConditionalExpr(Expression condition, Expression thenExpr, Expression elseExpr, SourceRange range) {
        super(range);
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
        if (thenExpr != null) setType(thenExpr.type());
    }

    public Expression condition() {
        return condition;
    }

    public Expression thenExpr() {
        return thenExpr;
    }

    public Expression elseExpr() {
        return elseExpr;
    }

    @Override
    public List<? extends Node> children() {
        return kids(condition, thenExpr, elseExpr);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitConditionalExpr(this);
    }
}
