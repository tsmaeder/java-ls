/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ArrayTypeNode extends TypeNode {

    private final TypeNode element;

    public ArrayTypeNode(TypeNode element, JType resolved, SourceRange range) {
        super(range, coalesce(resolved, element != null ? JType.array(element.resolvedType()) : null));
        this.element = element;
        if (element != null) element.setParent(this);
    }

    public TypeNode element() {
        return element;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, element, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayTypeNode(this);
    }
}
