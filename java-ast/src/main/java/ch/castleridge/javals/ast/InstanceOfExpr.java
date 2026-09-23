/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class InstanceOfExpr extends Expression {

    private final Expression expression;
    private final TypeNode type;
    private final Pattern pattern;

    public InstanceOfExpr(Expression expression, TypeNode type, Pattern pattern, SourceRange range) {
        super(range);
        this.expression = expression;
        this.type = type;
        this.pattern = pattern;
        setType(JType.Primitive.BOOLEAN);
    }

    public Expression expression() {
        return expression;
    }

    public TypeNode typeNode() {
        return type;
    }

    public Pattern pattern() {
        return pattern;
    }

    @Override
    public List<? extends Node> children() {
        return kids(expression, type, pattern);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitInstanceOfExpr(this);
    }
}
