/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ExprStmt extends Statement {
    private final Expression expression;
    public ExprStmt(Expression expression, SourceRange range) {
        super(range);
        this.expression = expression;
        if (expression != null) expression.setParent(this);
    }
    public Expression expression() { return expression; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, expression, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitExprStmt(this); }
}
