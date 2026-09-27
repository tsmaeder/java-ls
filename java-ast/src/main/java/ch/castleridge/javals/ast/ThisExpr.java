/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ThisExpr extends Expression {

    private final TypeNode qualifier;

    public ThisExpr(TypeNode qualifier, JType type, SourceRange range) {
        super(range, type);
        this.qualifier = qualifier;
        if (qualifier != null) qualifier.setParent(this);
    }

    public TypeNode qualifier() {
        return qualifier;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, qualifier, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitThisExpr(this);
    }
}
