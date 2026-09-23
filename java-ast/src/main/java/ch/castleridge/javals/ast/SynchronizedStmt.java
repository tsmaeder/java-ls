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
    }
    public Expression lock() { return lock; }
    public Block body() { return body; }
    @Override public List<? extends Node> children() { return kids(lock, body); }
    @Override public void accept(AstVisitor visitor) { visitor.visitSynchronizedStmt(this); }
}
