/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class DoWhileStmt extends Statement {
    private final Statement body;
    private final Expression condition;
    public DoWhileStmt(Statement body, Expression condition, SourceRange range) {
        super(range);
        this.body = body;
        this.condition = condition;
    }
    public Statement body() { return body; }
    public Expression condition() { return condition; }
    @Override public List<? extends Node> children() { return kids(body, condition); }
    @Override public void accept(AstVisitor visitor) { visitor.visitDoWhileStmt(this); }
}
