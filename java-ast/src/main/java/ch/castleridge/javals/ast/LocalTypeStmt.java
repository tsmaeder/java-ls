/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class LocalTypeStmt extends Statement {
    private final TypeDecl type;
    public LocalTypeStmt(TypeDecl type, SourceRange range) {
        super(range);
        this.type = type;
        if (type != null) type.setParent(this);
    }
    public TypeDecl type() { return type; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, type, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitLocalTypeStmt(this); }
}
