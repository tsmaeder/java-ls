/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class BinaryExpr extends Expression {

    public enum Op {
        PLUS("+"), MINUS("-"), MULTIPLY("*"), DIVIDE("/"), REMAINDER("%"),
        AND("&"), OR("|"), XOR("^"),
        LEFT_SHIFT("<<"), RIGHT_SHIFT(">>"), UNSIGNED_RIGHT_SHIFT(">>>"),
        LESS("<"), LESS_EQUAL("<="), GREATER(">"), GREATER_EQUAL(">="),
        EQUAL("=="), NOT_EQUAL("!="),
        CONDITIONAL_AND("&&"), CONDITIONAL_OR("||");

        private final String image;

        Op(String image) {
            this.image = image;
        }

        public String image() {
            return image;
        }
    }

    private final Op op;
    private final Expression left;
    private final Expression right;

    public BinaryExpr(Op op, Expression left, Expression right, SourceRange range) {
        super(range);
        this.op = op;
        this.left = left;
        this.right = right;
    }

    public Op op() {
        return op;
    }

    public Expression left() {
        return left;
    }

    public Expression right() {
        return right;
    }

    @Override
    public List<? extends Node> children() {
        return kids(left, right);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitBinaryExpr(this);
    }
}
