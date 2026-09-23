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

    public TypeName(List<Identifier> names, SourceRange range) {
        super(range);
        this.names = names == null ? List.of() : List.copyOf(names);
    }

    public List<Identifier> names() {
        return names;
    }

    public Identifier simpleName() {
        return names.isEmpty() ? null : names.get(names.size() - 1);
    }

    @Override
    public List<? extends Node> children() {
        return kids(names);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeName(this);
    }
}
