/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class EnumConstantSymbol implements Symbol {

    private final String name;
    private final TypeSymbol owner;
    private final SymbolKey key;

    public EnumConstantSymbol(String name, TypeSymbol owner, SymbolKey key) {
        this.name = name == null ? "" : name;
        this.owner = owner;
        this.key = key;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return owner == null ? JType.ERROR : owner.type();
    }

    public TypeSymbol owner() {
        return owner;
    }

    @Override
    public SymbolKey key() {
        return key;
    }
}
