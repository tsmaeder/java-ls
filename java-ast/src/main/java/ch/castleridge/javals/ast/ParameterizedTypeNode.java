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

    public ParameterizedTypeNode(TypeNode raw,
                                 List<TypeNode> typeArguments,
                                 JType resolved,
                                 SourceRange range) {
        super(range, coalesce(resolved, null));
        this.raw = raw;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        if (raw != null) raw.setParent(this);
        for (Node __c : this.typeArguments) __c.setParent(this);
    }

    public TypeNode raw() {
        return raw;
    }

    public List<TypeNode> typeArguments() {
        return typeArguments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, raw, offset);
        for (Node __c : typeArguments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitParameterizedTypeNode(this);
    }
}
