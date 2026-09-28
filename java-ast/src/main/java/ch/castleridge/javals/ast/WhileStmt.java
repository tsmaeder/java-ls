/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class WhileStmt extends Statement {
    private final Expression condition;
    private final Statement body;
    public WhileStmt(Expression condition, Statement body, SourceRange range) {
        super(range);
        this.condition = condition;
        this.body = body;
        if (condition != null) condition.setParent(this);
        if (body != null) body.setParent(this);
    }
    public Expression condition() { return condition; }
    public Statement body() { return body; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, condition, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitWhileStmt(this); }
}
