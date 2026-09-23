/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class FieldSymbol implements Symbol {

    private final String name;
    private final JType type;
    private final TypeSymbol owner;
    private final SymbolKey key;
    private final boolean synthetic;

    public FieldSymbol(String name, JType type, TypeSymbol owner, SymbolKey key, boolean synthetic) {
        this.name = name == null ? "" : name;
        this.type = type == null ? JType.ERROR : type;
        this.owner = owner;
        this.key = key;
        this.synthetic = synthetic;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return type;
    }

    public TypeSymbol owner() {
        return owner;
    }

    @Override
    public SymbolKey key() {
        return key;
    }

    @Override
    public boolean synthetic() {
        return synthetic;
    }
}
