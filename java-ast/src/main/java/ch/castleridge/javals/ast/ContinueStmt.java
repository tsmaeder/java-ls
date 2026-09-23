/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ContinueStmt extends Statement {
    private final Identifier label;
    public ContinueStmt(Identifier label, SourceRange range) {
        super(range);
        this.label = label;
    }
    public Identifier label() { return label; }
    @Override public List<? extends Node> children() { return kids(label); }
    @Override public void accept(AstVisitor visitor) { visitor.visitContinueStmt(this); }
}
