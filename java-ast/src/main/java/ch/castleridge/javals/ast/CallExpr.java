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
                    SourceRange range) {
        super(range);
        this.receiver = receiver;
        this.name = name;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (name != null && name.symbol() != null) setType(name.symbol().type());
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
    public List<? extends Node> children() {
        return kids(receiver, name, typeArguments, arguments);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCallExpr(this);
    }
}
