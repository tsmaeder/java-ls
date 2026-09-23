/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class Identifier extends Node {

    private final String name;
    private Symbol symbol;

    public Identifier(String name, SourceRange range) {
        this(name, range, null);
    }

    public Identifier(String name, SourceRange range, Symbol symbol) {
        super(range);
        this.name = name == null ? "" : name;
        this.symbol = symbol;
    }

    public String name() {
        return name;
    }

    public Symbol symbol() {
        return symbol;
    }

    public void setSymbol(Symbol symbol) {
        if (frozen()) throw new IllegalStateException("frozen");
        this.symbol = symbol;
    }

    @Override
    public List<? extends Node> children() {
        return List.of();
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitIdentifier(this);
    }
}
