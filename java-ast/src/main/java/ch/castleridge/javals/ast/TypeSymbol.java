/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;

public final class TypeSymbol implements Symbol {

    private static final Symbol[] NO_MEMBERS = new Symbol[0];

    private final TypeDeclKind kind;
    private final String jvmBinaryName;
    private final SymbolKey key;
    private final JType type;
    private final TypeVarSymbol[] typeParams;
    private final ArrayList<Symbol> members = new ArrayList<>();
    private final boolean synthetic;

    public TypeSymbol(TypeDeclKind kind, String jvmBinaryName, SymbolKey key, TypeVarSymbol[] typeParams) {
        this(kind, jvmBinaryName, key, typeParams, false);
    }

    public TypeSymbol(TypeDeclKind kind,
                      String jvmBinaryName,
                      SymbolKey key,
                      TypeVarSymbol[] typeParams,
                      boolean synthetic) {
        this.kind = kind == null ? TypeDeclKind.CLASS : kind;
        this.jvmBinaryName = jvmBinaryName == null ? "" : jvmBinaryName;
        this.key = key;
        this.type = JType.Declared.of(this.jvmBinaryName.replace('.', '/'));
        this.typeParams = EmptyArrays.orEmpty(typeParams, EmptyArrays.TYPE_VAR_SYMBOL);
        this.synthetic = synthetic;
    }

    public TypeDeclKind kind() {
        return kind;
    }

    public String jvmBinaryName() {
        return jvmBinaryName;
    }

    public TypeVarSymbol[] typeParams() {
        return typeParams;
    }

    public Symbol[] members() {
        return members.isEmpty() ? NO_MEMBERS : members.toArray(NO_MEMBERS);
    }

    public void addMember(Symbol member) {
        if (member != null) members.add(member);
    }

    @Override
    public String name() {
        int cut = Math.max(jvmBinaryName.lastIndexOf('/'), Math.max(jvmBinaryName.lastIndexOf('.'), jvmBinaryName.lastIndexOf('$')));
        return cut < 0 ? jvmBinaryName : jvmBinaryName.substring(cut + 1);
    }

    @Override
    public JType type() {
        return type;
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
