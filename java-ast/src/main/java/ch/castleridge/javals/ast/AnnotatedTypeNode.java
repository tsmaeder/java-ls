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

    public AnnotatedTypeNode(List<Annotation> annotations, TypeNode inner, SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.inner = inner;
        if (inner != null) setResolvedType(inner.resolvedType());
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public TypeNode inner() {
        return inner;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, inner);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnotatedTypeNode(this);
    }
}
