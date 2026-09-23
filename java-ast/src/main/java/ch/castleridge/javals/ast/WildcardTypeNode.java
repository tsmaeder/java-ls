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

    public WildcardTypeNode(BoundKind boundKind, TypeNode bound, SourceRange range) {
        super(range);
        this.boundKind = boundKind == null ? BoundKind.UNBOUNDED : boundKind;
        this.bound = bound;
        setResolvedType(switch (this.boundKind) {
            case UNBOUNDED -> JType.Wildcard.unbounded();
            case EXTENDS -> new JType.Wildcard(JType.Wildcard.BoundKind.EXTENDS,
                    bound == null ? JType.ERROR : bound.resolvedType());
            case SUPER -> new JType.Wildcard(JType.Wildcard.BoundKind.SUPER,
                    bound == null ? JType.ERROR : bound.resolvedType());
        });
    }

    public BoundKind boundKind() {
        return boundKind;
    }

    public TypeNode bound() {
        return bound;
    }

    @Override
    public List<? extends Node> children() {
        return kids(bound);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitWildcardTypeNode(this);
    }
}
