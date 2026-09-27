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
        if (condition != null) condition.setParent(this);
        if (thenStmt != null) thenStmt.setParent(this);
        if (elseStmt != null) elseStmt.setParent(this);
    }
    public Expression condition() { return condition; }
    public Statement thenStmt() { return thenStmt; }
    public Statement elseStmt() { return elseStmt; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, condition, offset);
        deepest = deeper(deepest, thenStmt, offset);
        deepest = deeper(deepest, elseStmt, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitIfStmt(this); }
}
