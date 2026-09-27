/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class TypeName extends TypeNode {

    private final List<Identifier> names;

    public TypeName(List<Identifier> names, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(names)));
        this.names = names == null ? List.of() : List.copyOf(names);
        for (Node __c : this.names) __c.setParent(this);
    }

    private static JType defaultFrom(List<Identifier> names) {
        if (names == null || names.isEmpty()) return null;
        Identifier last = names.get(names.size() - 1);
        return last != null && last.symbol() != null ? last.symbol().type() : null;
    }

    public List<Identifier> names() {
        return names;
    }

    public Identifier simpleName() {
        return names.isEmpty() ? null : names.get(names.size() - 1);
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
