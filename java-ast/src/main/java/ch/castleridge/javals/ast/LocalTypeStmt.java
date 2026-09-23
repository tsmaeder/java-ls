/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class LocalTypeStmt extends Statement {
    private final TypeDecl type;
    public LocalTypeStmt(TypeDecl type, SourceRange range) {
        super(range);
        this.type = type;
    }
    public TypeDecl type() { return type; }
    @Override public List<? extends Node> children() { return kids(type); }
    @Override public void accept(AstVisitor visitor) { visitor.visitLocalTypeStmt(this); }
}
