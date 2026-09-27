/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class VarFragment extends Node {

    private final Identifier name;
    private final int extraDimensions;
    private final Expression initializer;
    private final Symbol symbol;

    public VarFragment(Identifier name, int extraDimensions, Expression initializer, Symbol symbol, SourceRange range) {
        super(range);
        this.name = name;
        this.extraDimensions = extraDimensions;
        this.initializer = initializer;
        this.symbol = symbol;
        if (name != null) name.setParent(this);
        if (initializer != null) initializer.setParent(this);
    }

    public Identifier name() {
        return name;
    }

    public int extraDimensions() {
        return extraDimensions;
    }

    public Expression initializer() {
        return initializer;
    }

    public Symbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, name, offset);
        deepest = deeper(deepest, initializer, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitVarFragment(this);
    }
}
