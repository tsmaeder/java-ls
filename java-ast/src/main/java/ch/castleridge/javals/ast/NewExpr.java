/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class NewExpr extends Expression {

    private final Expression enclosing;
    private final TypeNode typeNode;
    private final TypeNode[] typeArguments;
    private final Expression[] arguments;
    private final TypeDecl anonymousBody;
    private final MethodSymbol constructor;

    public NewExpr(Expression enclosing,
                   TypeNode typeNode,
                   TypeNode[] typeArguments,
                   Expression[] arguments,
                   TypeDecl anonymousBody,
                   MethodSymbol constructor,
                   JType type,
                   SourceRange range) {
        super(range, coalesce(type, typeNode != null ? typeNode.resolvedType() : null));
        this.enclosing = enclosing;
        this.typeNode = typeNode;
        this.typeArguments = EmptyArrays.orEmpty(typeArguments, EmptyArrays.TYPE_NODE);
        this.arguments = EmptyArrays.orEmpty(arguments, EmptyArrays.EXPRESSION);
        this.anonymousBody = anonymousBody;
        this.constructor = constructor;
        if (enclosing != null) enclosing.setParent(this);
        if (typeNode != null) typeNode.setParent(this);
        for (Node __c : this.typeArguments) __c.setParent(this);
        for (Node __c : this.arguments) __c.setParent(this);
        if (anonymousBody != null) anonymousBody.setParent(this);
    }

    public Expression enclosing() {
        return enclosing;
    }

    public TypeNode typeNode() {
        return typeNode;
    }

    public TypeNode[] typeArguments() {
        return typeArguments;
    }

    public Expression[] arguments() {
        return arguments;
    }

    public TypeDecl anonymousBody() {
        return anonymousBody;
    }

    public MethodSymbol constructor() {
        return constructor;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, enclosing, offset);
        deepest = deeper(deepest, typeNode, offset);
        for (Node __c : typeArguments) deepest = deeper(deepest, __c, offset);
        for (Node __c : arguments) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, anonymousBody, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitNewExpr(this);
    }
}
