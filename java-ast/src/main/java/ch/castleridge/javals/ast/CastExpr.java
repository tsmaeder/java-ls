/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class CastExpr extends Expression {

    private final TypeNode type;
    private final Expression expression;

    public CastExpr(TypeNode type, Expression expression, SourceRange range) {
        super(range);
        this.type = type;
        this.expression = expression;
        if (type != null) setType(type.resolvedType());
    }

    public TypeNode typeNode() {
        return type;
    }

    public Expression expression() {
        return expression;
    }

    @Override
    public List<? extends Node> children() {
        return kids(type, expression);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCastExpr(this);
    }
}
