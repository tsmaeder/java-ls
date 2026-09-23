/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class EmptyStmt extends Statement {
    public EmptyStmt(SourceRange range) { super(range); }
    @Override public List<? extends Node> children() { return List.of(); }
    @Override public void accept(AstVisitor visitor) { visitor.visitEmptyStmt(this); }
}
