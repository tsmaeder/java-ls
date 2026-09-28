/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class AnnotatedTypeNode extends TypeNode {

    private final Annotation[] annotations;
    private final TypeNode inner;

    public AnnotatedTypeNode(Annotation[] annotations,
                             TypeNode inner,
                             JType resolved,
                             SourceRange range) {
        super(range, coalesce(resolved, inner != null ? inner.resolvedType() : null));
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.inner = inner;
        for (Node __c : this.annotations) __c.setParent(this);
        if (inner != null) inner.setParent(this);
    }

    public Annotation[] annotations() {
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
