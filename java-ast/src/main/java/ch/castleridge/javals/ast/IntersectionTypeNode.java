/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

public final class IntersectionTypeNode extends TypeNode {

    private final List<TypeNode> bounds;

    public IntersectionTypeNode(List<TypeNode> bounds, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(bounds)));
        this.bounds = bounds == null ? List.of() : List.copyOf(bounds);
        for (Node __c : this.bounds) __c.setParent(this);
    }

    private static JType defaultFrom(List<TypeNode> bounds) {
        List<TypeNode> bs = bounds == null ? List.of() : bounds;
        List<JType> types = new ArrayList<>();
        for (TypeNode bound : bs) types.add(bound.resolvedType());
        return new JType.Intersection(types);
    }

    public List<TypeNode> bounds() {
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
