/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class CallExpr extends Expression {

    private final Expression receiver;
    private final Identifier name;
    private final List<TypeNode> typeArguments;
    private final List<Expression> arguments;

    public CallExpr(Expression receiver,
                    Identifier name,
                    List<TypeNode> typeArguments,
                    List<Expression> arguments,
                    JType type,
                    SourceRange range) {
        super(range, coalesce(type, name != null && name.symbol() != null ? name.symbol().type() : null));
        this.receiver = receiver;
        this.name = name;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (receiver != null) receiver.setParent(this);
        if (name != null) name.setParent(this);
        for (Node __c : this.typeArguments) __c.setParent(this);
        for (Node __c : this.arguments) __c.setParent(this);
    }

    public Expression receiver() {
        return receiver;
    }

    public Identifier name() {
        return name;
    }

    public List<TypeNode> typeArguments() {
        return typeArguments;
    }

    public List<Expression> arguments() {
        return arguments;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, receiver, offset);
        deepest = deeper(deepest, name, offset);
        for (Node __c : typeArguments) deepest = deeper(deepest, __c, offset);
        for (Node __c : arguments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCallExpr(this);
    }
}
