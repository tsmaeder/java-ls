/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class NameExpr extends Expression {

    private final Identifier name;

    public NameExpr(Identifier name, SourceRange range) {
        super(range);
        this.name = name;
        if (name != null && name.symbol() != null) setType(name.symbol().type());
    }

    public Identifier name() {
        return name;
    }

    @Override
    public List<? extends Node> children() {
        return kids(name);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNameExpr(this);
    }
}
