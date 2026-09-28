/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class TypeVarSymbol implements Symbol {

    private final String name;
    private final JType[] bounds;
    private final SymbolKey key;

    public TypeVarSymbol(String name, JType[] bounds) {
        this.name = name == null ? "" : name;
        this.bounds = EmptyArrays.orEmpty(bounds, EmptyArrays.JTYPE);
        this.key = SymbolKey.local(this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return new JType.TypeVar(name);
    }

    public JType[] bounds() {
        return bounds;
    }

    @Override
    public SymbolKey key() {
        return key;
    }
}
