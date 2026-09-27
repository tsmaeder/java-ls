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
        if (condition != null) condition.setParent(this);
        if (message != null) message.setParent(this);
    }
    public Expression condition() { return condition; }
    public Expression message() { return message; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, condition, offset);
        deepest = deeper(deepest, message, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitAssertStmt(this); }
}
