/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class TypePattern extends Pattern {
    private final TypeNode type;
    private final Identifier name;
    private final LocalSymbol symbol;
    public TypePattern(TypeNode type, Identifier name, LocalSymbol symbol, SourceRange range) {
        super(range);
        this.type = type;
        this.name = name;
        this.symbol = symbol;
        if (type != null) type.setParent(this);
        if (name != null) name.setParent(this);
    }
    public TypeNode type() { return type; }
    public Identifier name() { return name; }
    public LocalSymbol symbol() { return symbol; }
    public boolean unnamed() { return name == null || "_".equals(name.name()); }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, type, offset);
        deepest = deeper(deepest, name, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitTypePattern(this); }
}
