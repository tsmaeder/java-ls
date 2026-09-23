/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class NewExpr extends Expression {

    private final Expression enclosing;
    private final TypeNode type;
    private final List<TypeNode> typeArguments;
    private final List<Expression> arguments;
    private final TypeDecl anonymousBody;
    private final MethodSymbol constructor;

    public NewExpr(Expression enclosing,
                   TypeNode type,
                   List<TypeNode> typeArguments,
                   List<Expression> arguments,
                   TypeDecl anonymousBody,
                   MethodSymbol constructor,
                   SourceRange range) {
        super(range);
        this.enclosing = enclosing;
        this.type = type;
        this.typeArguments = typeArguments == null ? List.of() : List.copyOf(typeArguments);
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
        this.anonymousBody = anonymousBody;
        this.constructor = constructor;
        if (type != null) setType(type.resolvedType());
    }

    public Expression enclosing() {
        return enclosing;
    }

    public TypeNode typeNode() {
        return type;
    }

    public List<TypeNode> typeArguments() {
        return typeArguments;
    }

    public List<Expression> arguments() {
        return arguments;
    }

    public TypeDecl anonymousBody() {
        return anonymousBody;
    }

    public MethodSymbol constructor() {
        return constructor;
    }

    @Override
    public List<? extends Node> children() {
        return kids(enclosing, type, typeArguments, arguments, anonymousBody);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNewExpr(this);
    }
}
