/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ArrayInitExpr extends Expression {

    private final List<Expression> elements;

    public ArrayInitExpr(List<Expression> elements, SourceRange range) {
        super(range);
        this.elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public List<Expression> elements() {
        return elements;
    }

    @Override
    public List<? extends Node> children() {
        return kids(elements);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitArrayInitExpr(this);
    }
}
