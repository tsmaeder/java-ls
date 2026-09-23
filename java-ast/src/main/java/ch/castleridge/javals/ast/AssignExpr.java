/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class AssignExpr extends Expression {

    public enum Op {
        ASSIGN("="), PLUS("+="), MINUS("-="), MULTIPLY("*="), DIVIDE("/="), REMAINDER("%="),
        AND("&="), OR("|="), XOR("^="),
        LEFT_SHIFT("<<="), RIGHT_SHIFT(">>="), UNSIGNED_RIGHT_SHIFT(">>>=");

        private final String image;

        Op(String image) {
            this.image = image;
        }

        public String image() {
            return image;
        }
    }

    private final Op op;
    private final Expression target;
    private final Expression value;

    public AssignExpr(Op op, Expression target, Expression value, SourceRange range) {
        super(range);
        this.op = op == null ? Op.ASSIGN : op;
        this.target = target;
        this.value = value;
        if (target != null) setType(target.type());
    }

    public Op op() {
        return op;
    }

    public Expression target() {
        return target;
    }

    public Expression value() {
        return value;
    }

    @Override
    public List<? extends Node> children() {
        return kids(target, value);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAssignExpr(this);
    }
}
