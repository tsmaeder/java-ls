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

    public Annotation(TypeName name, List<AnnoArg> arguments, JType type, SourceRange range) {
        super(range, type);
        this.name = name;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (name != null) name.setParent(this);
        for (Node __c : this.arguments) __c.setParent(this);
    }

    public TypeName name() {
        return name;
    }

    public List<AnnoArg> arguments() {
        return arguments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, name, offset);
        for (Node __c : arguments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitAnnotation(this);
    }
}
