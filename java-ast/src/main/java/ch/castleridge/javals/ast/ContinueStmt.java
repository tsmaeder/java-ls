/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ContinueStmt extends Statement {
    private final Identifier label;
    public ContinueStmt(Identifier label, SourceRange range) {
        super(range);
        this.label = label;
        if (label != null) label.setParent(this);
    }
    public Identifier label() { return label; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, label, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitContinueStmt(this); }
}
