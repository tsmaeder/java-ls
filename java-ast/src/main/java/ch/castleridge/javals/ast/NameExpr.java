/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class NameExpr extends Expression {

    private final Identifier name;

    public NameExpr(Identifier name, JType type, SourceRange range) {
        super(range, coalesce(type, name != null && name.symbol() != null ? name.symbol().type() : null));
        this.name = name;
        if (name != null) name.setParent(this);
    }

    public Identifier name() {
        return name;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, name, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNameExpr(this);
    }
}
