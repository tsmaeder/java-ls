/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class MethodSymbol implements Symbol {

    private final String name;
    private final JType returnType;
    private final List<JType> parameterTypes;
    private final TypeSymbol owner;
    private final SymbolKey key;
    private final boolean constructor;
    private final boolean synthetic;

    public MethodSymbol(String name,
                        JType returnType,
                        List<JType> parameterTypes,
                        TypeSymbol owner,
                        SymbolKey key,
                        boolean constructor,
                        boolean synthetic) {
        this.name = name == null ? "" : name;
        this.returnType = returnType == null ? JType.ERROR : returnType;
        this.parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        this.owner = owner;
        this.key = key;
        this.constructor = constructor;
        this.synthetic = synthetic;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JType type() {
        return returnType;
    }

    public JType returnType() {
        return returnType;
    }

    public List<JType> parameterTypes() {
        return parameterTypes;
    }

    public TypeSymbol owner() {
        return owner;
    }

    public boolean constructor() {
        return constructor;
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
