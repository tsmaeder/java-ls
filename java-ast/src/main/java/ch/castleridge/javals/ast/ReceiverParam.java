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
        for (Node __c : this.annotations) __c.setParent(this);
        if (type != null) type.setParent(this);
        if (name != null) name.setParent(this);
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
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, type, offset);
        deepest = deeper(deepest, name, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitReceiverParam(this);
    }
}
