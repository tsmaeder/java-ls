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
                        SourceRange range) {
        super(range);
        this.elementType = elementType;
        this.dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        this.initializer = initializer;
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
    public List<? extends Node> children() {
        return kids(elementType, dimensions, initializer);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNewArrayExpr(this);
    }
}
