/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class TypeName extends TypeNode {

    private final Identifier[] names;

    public TypeName(Identifier[] names, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(names)));
        this.names = EmptyArrays.orEmpty(names, EmptyArrays.IDENTIFIER);
        for (Node __c : this.names) __c.setParent(this);
    }

    private static JType defaultFrom(Identifier[] names) {
        if (names == null || names.length == 0) return null;
        Identifier last = names[names.length - 1];
        return last != null && last.symbol() != null ? last.symbol().type() : null;
    }

    public Identifier[] names() {
        return names;
    }

    public Identifier simpleName() {
        return names.length == 0 ? null : names[names.length - 1];
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : names) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeName(this);
    }
}
