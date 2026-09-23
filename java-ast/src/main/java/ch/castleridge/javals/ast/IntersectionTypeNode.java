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

    public IntersectionTypeNode(List<TypeNode> bounds, SourceRange range) {
        super(range);
        this.bounds = bounds == null ? List.of() : List.copyOf(bounds);
        List<JType> types = new ArrayList<>();
        for (TypeNode bound : this.bounds) types.add(bound.resolvedType());
        setResolvedType(new JType.Intersection(types));
    }

    public List<TypeNode> bounds() {
        return bounds;
    }

    @Override
    public List<? extends Node> children() {
        return kids(bounds);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitIntersectionTypeNode(this);
    }
}
