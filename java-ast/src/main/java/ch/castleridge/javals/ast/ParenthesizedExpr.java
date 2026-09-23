/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ParenthesizedExpr extends Expression {

    private final Expression expression;

    public ParenthesizedExpr(Expression expression, SourceRange range) {
        super(range);
        this.expression = expression;
        if (expression != null) setType(expression.type());
    }

    public Expression expression() {
        return expression;
    }

    @Override
    public List<? extends Node> children() {
        return kids(expression);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitParenthesizedExpr(this);
    }
}
