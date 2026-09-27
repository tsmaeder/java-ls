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
        if (body != null) body.setParent(this);
        if (condition != null) condition.setParent(this);
    }
    public Statement body() { return body; }
    public Expression condition() { return condition; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, body, offset);
        deepest = deeper(deepest, condition, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitDoWhileStmt(this); }
}
