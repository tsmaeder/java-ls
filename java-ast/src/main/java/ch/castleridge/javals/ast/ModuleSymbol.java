/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class ModuleSymbol implements Symbol {

    private final String name;
    private final SymbolKey key;

    public ModuleSymbol(String name, SymbolKey key) {
        this.name = name == null ? "" : name;
        this.key = key;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return JType.ERROR;
    }

    @Override
    public SymbolKey key() {
        return key;
    }
}
