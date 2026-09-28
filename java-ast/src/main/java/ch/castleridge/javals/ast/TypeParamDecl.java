/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class TypeParamDecl extends Node {

    private final Annotation[] annotations;
    private final Identifier name;
    private final TypeNode[] bounds;
    private final TypeVarSymbol symbol;

    public TypeParamDecl(Annotation[] annotations,
                         Identifier name,
                         TypeNode[] bounds,
                         TypeVarSymbol symbol,
                         SourceRange range) {
        super(range);
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.name = name;
        this.bounds = EmptyArrays.orEmpty(bounds, EmptyArrays.TYPE_NODE);
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (name != null) name.setParent(this);
        for (Node __c : this.bounds) __c.setParent(this);
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public Identifier name() {
        return name;
    }

    public TypeNode[] bounds() {
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
