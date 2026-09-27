/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class SynchronizedStmt extends Statement {
    private final Expression lock;
    private final Block body;
    public SynchronizedStmt(Expression lock, Block body, SourceRange range) {
        super(range);
        this.lock = lock;
        this.body = body;
        if (lock != null) lock.setParent(this);
        if (body != null) body.setParent(this);
    }
    public Expression lock() { return lock; }
    public Block body() { return body; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, lock, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitSynchronizedStmt(this); }
}
