/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class AssertStmt extends Statement {
    private final Expression condition;
    private final Expression message;
    public AssertStmt(Expression condition, Expression message, SourceRange range) {
        super(range);
        this.condition = condition;
        this.message = message;
    }
    public Expression condition() { return condition; }
    public Expression message() { return message; }
    @Override public List<? extends Node> children() { return kids(condition, message); }
    @Override public void accept(AstVisitor visitor) { visitor.visitAssertStmt(this); }
}
