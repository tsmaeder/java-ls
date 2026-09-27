/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class NewArrayExpr extends Expression {

    private final TypeNode elementType;
    private final List<Expression> dimensions;
    private final ArrayInitExpr initializer;

    public NewArrayExpr(TypeNode elementType,
                        List<Expression> dimensions,
                        ArrayInitExpr initializer,
                        JType type,
                        SourceRange range) {
        super(range, type);
        this.elementType = elementType;
        this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        this.initializer = initializer;
        if (elementType != null) elementType.setParent(this);
        for (Node __c : this.dimensions) __c.setParent(this);
        if (initializer != null) initializer.setParent(this);
    }

    public TypeNode elementType() {
        return elementType;
    }

    public List<Expression> dimensions() {
        return dimensions;
    }

    public ArrayInitExpr initializer() {
        return initializer;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, elementType, offset);
        for (Node __c : dimensions) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, initializer, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNewArrayExpr(this);
    }
}
