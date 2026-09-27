/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class WildcardTypeNode extends TypeNode {

    public enum BoundKind { UNBOUNDED, EXTENDS, SUPER }

    private final BoundKind boundKind;
    private final TypeNode bound;

    public WildcardTypeNode(BoundKind boundKind, TypeNode bound, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, defaultFrom(boundKind, bound)));
        this.boundKind = boundKind == null ? BoundKind.UNBOUNDED : boundKind;
        this.bound = bound;
        if (bound != null) bound.setParent(this);
    }

    private static JType defaultFrom(BoundKind boundKind, TypeNode bound) {
        BoundKind kind = boundKind == null ? BoundKind.UNBOUNDED : boundKind;
        return switch (kind) {
            case UNBOUNDED -> JType.Wildcard.unbounded();
            case EXTENDS -> new JType.Wildcard(JType.Wildcard.BoundKind.EXTENDS,
                    bound == null ? JType.ERROR : bound.resolvedType());
            case SUPER -> new JType.Wildcard(JType.Wildcard.BoundKind.SUPER,
                    bound == null ? JType.ERROR : bound.resolvedType());
        };
    }

    public BoundKind boundKind() {
        return boundKind;
    }

    public TypeNode bound() {
        return bound;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, bound, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitWildcardTypeNode(this);
    }
}
