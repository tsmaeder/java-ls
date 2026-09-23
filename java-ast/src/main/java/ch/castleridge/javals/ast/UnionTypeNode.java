/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

public final class UnionTypeNode extends TypeNode {

    private final List<TypeNode> alternatives;

    public UnionTypeNode(List<TypeNode> alternatives, SourceRange range) {
        super(range);
        this.alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        List<JType> types = new ArrayList<>();
        for (TypeNode alt : this.alternatives) types.add(alt.resolvedType());
        setResolvedType(new JType.Union(types));
    }

    public List<TypeNode> alternatives() {
        return alternatives;
    }

    @Override
    public List<? extends Node> children() {
        return kids(alternatives);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitUnionTypeNode(this);
    }
}
