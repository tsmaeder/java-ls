/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class SuperExpr extends Expression {

    private final TypeNode qualifier;

    public SuperExpr(TypeNode qualifier, SourceRange range) {
        super(range);
        this.qualifier = qualifier;
    }

    public TypeNode qualifier() {
        return qualifier;
    }

    @Override
    public List<? extends Node> children() {
        return kids(qualifier);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitSuperExpr(this);
    }
}
