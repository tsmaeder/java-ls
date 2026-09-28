/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class CallExpr extends Expression {

    private final Expression receiver;
    private final Identifier name;
    private final TypeNode[] typeArguments;
    private final Expression[] arguments;

    public CallExpr(Expression receiver,
                    Identifier name,
                    TypeNode[] typeArguments,
                    Expression[] arguments,
                    JType type,
                    SourceRange range) {
        super(range, coalesce(type, name != null && name.symbol() != null ? name.symbol().type() : null));
        this.receiver = receiver;
        this.name = name;
        this.typeArguments = EmptyArrays.orEmpty(typeArguments, EmptyArrays.TYPE_NODE);
        this.arguments = EmptyArrays.orEmpty(arguments, EmptyArrays.EXPRESSION);
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

    public TypeNode[] typeArguments() {
        return typeArguments;
    }

    public Expression[] arguments() {
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
