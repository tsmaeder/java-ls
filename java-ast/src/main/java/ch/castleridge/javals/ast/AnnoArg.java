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
    }

    public Identifier name() {
        return name;
    }

    public Expression value() {
        return value;
    }

    @Override
    public List<? extends Node> children() {
        return kids(name, value);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnoArg(this);
    }
}
