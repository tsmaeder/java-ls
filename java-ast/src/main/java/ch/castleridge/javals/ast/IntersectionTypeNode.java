/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public final class IntersectionTypeNode extends TypeNode {

    private final TypeNode[] bounds;

    public IntersectionTypeNode(TypeNode[] bounds, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(bounds)));
        this.bounds = EmptyArrays.orEmpty(bounds, EmptyArrays.TYPE_NODE);
        for (Node __c : this.bounds) __c.setParent(this);
    }

    private static JType defaultFrom(TypeNode[] bounds) {
        TypeNode[] bs = EmptyArrays.orEmpty(bounds, EmptyArrays.TYPE_NODE);
        JType[] types = new JType[bs.length];
        for (int i = 0; i < bs.length; i++) types[i] = bs[i].resolvedType();
        return new JType.Intersection(types);
    }

    public TypeNode[] bounds() {
        return bounds;
    }

    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : bounds) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitIntersectionTypeNode(this);
    }
}
