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

    public UnionTypeNode(List<TypeNode> alternatives, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(alternatives)));
        this.alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        for (Node __c : this.alternatives) __c.setParent(this);
    }

    private static JType defaultFrom(List<TypeNode> alternatives) {
        List<TypeNode> alts = alternatives == null ? List.of() : alternatives;
        List<JType> types = new ArrayList<>();
        for (TypeNode alt : alts) types.add(alt.resolvedType());
        return new JType.Union(types);
    }

    public List<TypeNode> alternatives() {
        return alternatives;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : alternatives) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitUnionTypeNode(this);
    }
}
