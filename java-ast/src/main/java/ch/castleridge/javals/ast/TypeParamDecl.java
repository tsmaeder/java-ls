/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class TypeParamDecl extends Node {

    private final List<Annotation> annotations;
    private final Identifier name;
    private final List<TypeNode> bounds;
    private final TypeVarSymbol symbol;

    public TypeParamDecl(List<Annotation> annotations,
                         Identifier name,
                         List<TypeNode> bounds,
                         TypeVarSymbol symbol,
                         SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.name = name;
        this.bounds = bounds == null ? List.of() : List.copyOf(bounds);
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (name != null) name.setParent(this);
        for (Node __c : this.bounds) __c.setParent(this);
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public List<TypeNode> bounds() {
        return bounds;
    }

    public TypeVarSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, name, offset);
        for (Node __c : bounds) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeParamDecl(this);
    }
}
