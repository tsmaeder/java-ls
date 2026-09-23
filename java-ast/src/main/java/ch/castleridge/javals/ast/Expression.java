/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract sealed class Expression extends Node
        permits NameExpr, Select, CallExpr, NewExpr, NewArrayExpr, LiteralExpr, ThisExpr, SuperExpr,
                BinaryExpr, UnaryExpr, AssignExpr, ConditionalExpr, CastExpr, InstanceOfExpr,
                ArrayAccessExpr, LambdaExpr, MemberRefExpr, SwitchExpr, ClassLiteralExpr,
                ParenthesizedExpr, ArrayInitExpr, ErroneousExpr, Annotation {

    private JType type = JType.ERROR;

    protected Expression(SourceRange range) {
        super(range);
    }

    public final JType type() {
        return type;
    }

    public final void setType(JType type) {
        if (frozen()) throw new IllegalStateException("frozen");
        this.type = type == null ? JType.ERROR : type;
    }
}
