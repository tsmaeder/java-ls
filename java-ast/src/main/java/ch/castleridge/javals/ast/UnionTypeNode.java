/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class UnionTypeNode extends TypeNode {

    private final TypeNode[] alternatives;

    public UnionTypeNode(TypeNode[] alternatives, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(alternatives)));
        this.alternatives = EmptyArrays.orEmpty(alternatives, EmptyArrays.TYPE_NODE);
        for (Node __c : this.alternatives) __c.setParent(this);
    }

    private static JType defaultFrom(TypeNode[] alternatives) {
        TypeNode[] alts = EmptyArrays.orEmpty(alternatives, EmptyArrays.TYPE_NODE);
        JType[] types = new JType[alts.length];
        for (int i = 0; i < alts.length; i++) types[i] = alts[i].resolvedType();
        return new JType.Union(types);
    }

    public TypeNode[] alternatives() {
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
