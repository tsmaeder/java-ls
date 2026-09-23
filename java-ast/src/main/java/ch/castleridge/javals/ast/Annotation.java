/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class Annotation extends Expression {

    private final TypeName name;
    private final List<AnnoArg> arguments;

    public Annotation(TypeName name, List<AnnoArg> arguments, SourceRange range) {
        super(range);
        this.name = name;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public TypeName name() {
        return name;
    }

    public List<AnnoArg> arguments() {
        return arguments;
    }

    @Override
    public List<? extends Node> children() {
        return kids(name, arguments);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnotation(this);
    }
}
