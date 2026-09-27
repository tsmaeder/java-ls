/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class AnnotatedTypeNode extends TypeNode {

    private final List<Annotation> annotations;
    private final TypeNode inner;

    public AnnotatedTypeNode(List<Annotation> annotations,
                             TypeNode inner,
                             JType resolved,
                             SourceRange range) {
        super(range, coalesce(resolved, inner != null ? inner.resolvedType() : null));
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.inner = inner;
        for (Node __c : this.annotations) __c.setParent(this);
        if (inner != null) inner.setParent(this);
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public TypeNode inner() {
        return inner;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, inner, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnotatedTypeNode(this);
    }
}
