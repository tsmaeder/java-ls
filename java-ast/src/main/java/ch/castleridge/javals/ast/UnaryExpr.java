/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class UnaryExpr extends Expression {

    public enum Op {
        PLUS("+"), MINUS("-"), NOT("!"), COMPLEMENT("~"),
        PRE_INCREMENT("++"), PRE_DECREMENT("--"),
        POST_INCREMENT("++"), POST_DECREMENT("--");

        private final String image;

        Op(String image) {
            this.image = image;
        }

        public String image() {
            return image;
        }

        public boolean postfix() {
            return this == POST_INCREMENT || this == POST_DECREMENT;
        }
    }

    private final Op op;
    private final Expression expression;

    public UnaryExpr(Op op, Expression expression, JType type, SourceRange range) {
        super(range, type);
        this.op = op;
        this.expression = expression;
        if (expression != null) expression.setParent(this);
    }

    public Op op() {
        return op;
    }

    public Expression expression() {
        return expression;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, expression, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitUnaryExpr(this);
    }
}
