/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ReceiverParam extends Node {

    private final List<Annotation> annotations;
    private final TypeNode type;
    private final Identifier name;

    public ReceiverParam(List<Annotation> annotations, TypeNode type, Identifier name, SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.type = type;
        this.name = name;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public TypeNode type() {
        return type;
    }

    public Identifier name() {
        return name;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, type, name);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitReceiverParam(this);
    }
}
