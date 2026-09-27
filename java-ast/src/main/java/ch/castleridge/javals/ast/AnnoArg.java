/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class AnnoArg extends Node {

    private final Identifier name;
    private final Expression value;

    public AnnoArg(Identifier name, Expression value, SourceRange range) {
        super(range);
        this.name = name;
        this.value = value;
        if (name != null) name.setParent(this);
        if (value != null) value.setParent(this);
    }

    public Identifier name() {
        return name;
    }

    public Expression value() {
        return value;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, name, offset);
        deepest = deeper(deepest, value, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnoArg(this);
    }
}
