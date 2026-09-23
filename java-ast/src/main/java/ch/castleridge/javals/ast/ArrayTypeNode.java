/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ArrayTypeNode extends TypeNode {

    private final TypeNode element;

    public ArrayTypeNode(TypeNode element, SourceRange range) {
        super(range);
        this.element = element;
        if (element != null) setResolvedType(JType.array(element.resolvedType()));
    }

    public TypeNode element() {
        return element;
    }

    @Override
    public List<? extends Node> children() {
        return kids(element);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayTypeNode(this);
    }
}
