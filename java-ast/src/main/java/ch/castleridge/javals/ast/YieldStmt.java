/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class YieldStmt extends Statement {
    private final Expression value;
    public YieldStmt(Expression value, SourceRange range) {
        super(range);
        this.value = value;
    }
    public Expression value() { return value; }
    @Override public List<? extends Node> children() { return kids(value); }
    @Override public void accept(AstVisitor visitor) { visitor.visitYieldStmt(this); }
}
