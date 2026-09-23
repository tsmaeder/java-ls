/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class IfStmt extends Statement {
    private final Expression condition;
    private final Statement thenStmt;
    private final Statement elseStmt;
    public IfStmt(Expression condition, Statement thenStmt, Statement elseStmt, SourceRange range) {
        super(range);
        this.condition = condition;
        this.thenStmt = thenStmt;
        this.elseStmt = elseStmt;
    }
    public Expression condition() { return condition; }
    public Statement thenStmt() { return thenStmt; }
    public Statement elseStmt() { return elseStmt; }
    @Override public List<? extends Node> children() { return kids(condition, thenStmt, elseStmt); }
    @Override public void accept(AstVisitor visitor) { visitor.visitIfStmt(this); }
}
