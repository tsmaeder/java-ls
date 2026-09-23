/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ClassLiteralExpr extends Expression {

    private final TypeNode type;

    public ClassLiteralExpr(TypeNode type, SourceRange range) {
        super(range);
        this.type = type;
        setType(JType.Declared.of("java/lang/Class"));
    }

    public TypeNode typeNode() {
        return type;
    }

    @Override
    public List<? extends Node> children() {
        return kids(type);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitClassLiteralExpr(this);
    }
}
