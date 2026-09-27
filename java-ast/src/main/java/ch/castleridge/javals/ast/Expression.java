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

    private final JType type;

    protected Expression(SourceRange range, JType type) {
        super(range);
        this.type = type == null ? JType.ERROR : type;
    }

    public final JType type() {
        return type;
    }

    /** Prefer {@code type} when non-null and not ERROR; otherwise {@code fallback}. */
    protected static JType coalesce(JType type, JType fallback) {
        if (type != null && type != JType.ERROR) return type;
        return fallback == null ? JType.ERROR : fallback;
    }
}
