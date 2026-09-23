/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class PackageSymbol implements Symbol {

    private final String qualifiedName;
    private final SymbolKey key;

    public PackageSymbol(String qualifiedName, SymbolKey key) {
        this.qualifiedName = qualifiedName == null ? "" : qualifiedName;
        this.key = key;
    }

    @Override
    public String name() {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    public String qualifiedName() {
        return qualifiedName;
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
