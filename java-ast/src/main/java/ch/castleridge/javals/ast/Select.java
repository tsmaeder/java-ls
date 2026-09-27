/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class Select extends Expression {

    private final Expression receiver;
    private final Identifier name;

    public Select(Expression receiver, Identifier name, JType type, SourceRange range) {
        super(range, coalesce(type, name != null && name.symbol() != null ? name.symbol().type() : null));
        this.receiver = receiver;
        this.name = name;
        if (receiver != null) receiver.setParent(this);
        if (name != null) name.setParent(this);
    }

    public Expression receiver() {
        return receiver;
    }

    public Identifier name() {
        return name;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, receiver, offset);
        deepest = deeper(deepest, name, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitSelect(this);
    }
}
