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
        if (value != null) value.setParent(this);
    }
    public Expression value() { return value; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, value, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitYieldStmt(this); }
}
