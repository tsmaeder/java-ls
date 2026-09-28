/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class AssignExpr extends Expression {

    public enum Op {
        ASSIGN("="),
        PLUS_ASSIGN("+="), MINUS_ASSIGN("-="), MULTIPLY_ASSIGN("*="), DIVIDE_ASSIGN("/="),
        REMAINDER_ASSIGN("%="), AND_ASSIGN("&="), OR_ASSIGN("|="), XOR_ASSIGN("^="),
        LEFT_SHIFT_ASSIGN("<<="), RIGHT_SHIFT_ASSIGN(">>="), UNSIGNED_RIGHT_SHIFT_ASSIGN(">>>=");

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

    public AssignExpr(Op op, Expression target, Expression value, JType type, SourceRange range) {
        super(range, coalesce(type, target != null ? target.type() : null));
        this.op = op;
        this.target = target;
        this.value = value;
        if (target != null) target.setParent(this);
        if (value != null) value.setParent(this);
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
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, target, offset);
        deepest = deeper(deepest, value, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAssignExpr(this);
    }
}
