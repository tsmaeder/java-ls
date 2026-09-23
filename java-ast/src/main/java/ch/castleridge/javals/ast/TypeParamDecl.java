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
    public List<? extends Node> children() {
        return kids(annotations, name, bounds);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitTypeParamDecl(this);
    }
}
