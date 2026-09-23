/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class LiteralExpr extends Expression {

    public enum Kind {
        INT, LONG, FLOAT, DOUBLE, CHAR, STRING, TEXT_BLOCK, BOOLEAN, NULL
    }

    private final Kind kind;
    private final Object value;
    private final String image;

    public LiteralExpr(Kind kind, Object value, String image, SourceRange range) {
        super(range);
        this.kind = kind == null ? Kind.NULL : kind;
        this.value = value;
        this.image = image;
        setType(switch (this.kind) {
            case INT -> JType.Primitive.INT;
            case LONG -> JType.Primitive.LONG;
            case FLOAT -> JType.Primitive.FLOAT;
            case DOUBLE -> JType.Primitive.DOUBLE;
            case CHAR -> JType.Primitive.CHAR;
            case STRING, TEXT_BLOCK -> JType.Declared.of("java/lang/String");
            case BOOLEAN -> JType.Primitive.BOOLEAN;
            case NULL -> JType.NULL;
        });
    }

    public Kind kind() {
        return kind;
    }

    public Object value() {
        return value;
    }

    public String image() {
        return image;
    }

    @Override
    public List<? extends Node> children() {
        return List.of();
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitLiteralExpr(this);
    }
}
