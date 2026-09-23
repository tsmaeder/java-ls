/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class WhileStmt extends Statement {
    private final Expression condition;
    private final Statement body;
    public WhileStmt(Expression condition, Statement body, SourceRange range) {
        super(range);
        this.condition = condition;
        this.body = body;
    }
    public Expression condition() { return condition; }
    public Statement body() { return body; }
    @Override public List<? extends Node> children() { return kids(condition, body); }
    @Override public void accept(AstVisitor visitor) { visitor.visitWhileStmt(this); }
}
