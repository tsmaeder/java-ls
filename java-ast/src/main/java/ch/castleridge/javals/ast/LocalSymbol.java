/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class LocalSymbol implements Symbol {

    private final String name;
    private final JType type;
    private final SymbolKey key;

    public LocalSymbol(String name, JType type) {
        this.name = name == null ? "" : name;
        this.type = type == null ? JType.ERROR : type;
        this.key = SymbolKey.local(this.name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return type;
    }

    @Override
    public SymbolKey key() {
        return key;
    }
}
