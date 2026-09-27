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

    public ConditionalExpr(Expression condition,
                           Expression thenExpr,
                           Expression elseExpr,
                           JType type,
                           SourceRange range) {
        super(range, coalesce(type, thenExpr != null ? thenExpr.type() : null));
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
        if (condition != null) condition.setParent(this);
        if (thenExpr != null) thenExpr.setParent(this);
        if (elseExpr != null) elseExpr.setParent(this);
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
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, condition, offset);
        deepest = deeper(deepest, thenExpr, offset);
        deepest = deeper(deepest, elseExpr, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitConditionalExpr(this);
    }
}
