/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ParameterizedTypeNode extends TypeNode {

    private final TypeNode raw;
    private final List<TypeNode> typeArguments;

    public ParameterizedTypeNode(TypeNode raw, List<TypeNode> typeArguments, SourceRange range) {
        super(range);
        this.raw = raw;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
    }

    public TypeNode raw() {
        return raw;
    }

    public List<TypeNode> typeArguments() {
        return typeArguments;
    }

    @Override
    public List<? extends Node> children() {
        return kids(raw, typeArguments);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitParameterizedTypeNode(this);
    }
}
