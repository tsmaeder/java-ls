/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class MethodDecl extends Declaration {

    private final int modifiers;
    private final Annotation[] annotations;
    private final TypeParamDecl[] typeParams;
    private final TypeNode returnType;
    private final Identifier name;
    private final ReceiverParam receiver;
    private final ParamDecl[] parameters;
    private final TypeNode[] thrown;
    private final Block body;
    private final MethodSymbol symbol;

    public MethodDecl(int modifiers,
                      Annotation[] annotations,
                      TypeParamDecl[] typeParams,
                      TypeNode returnType,
                      Identifier name,
                      ReceiverParam receiver,
                      ParamDecl[] parameters,
                      TypeNode[] thrown,
                      Block body,
                      MethodSymbol symbol,
                      SourceRange range) {
        super(range);
        this.modifiers = modifiers;
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.typeParams = EmptyArrays.orEmpty(typeParams, EmptyArrays.TYPE_PARAM);
        this.returnType = returnType;
        this.name = name;
        this.receiver = receiver;
        this.parameters = EmptyArrays.orEmpty(parameters, EmptyArrays.PARAM_DECL);
        this.thrown = EmptyArrays.orEmpty(thrown, EmptyArrays.TYPE_NODE);
        this.body = body;
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        for (Node __c : this.typeParams) __c.setParent(this);
        if (returnType != null) returnType.setParent(this);
        if (name != null) name.setParent(this);
        if (receiver != null) receiver.setParent(this);
        for (Node __c : this.parameters) __c.setParent(this);
        for (Node __c : this.thrown) __c.setParent(this);
        if (body != null) body.setParent(this);
    }

    public int modifiers() {
        return modifiers;
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public TypeParamDecl[] typeParams() {
        return typeParams;
    }

    public TypeNode returnType() {
        return returnType;
    }

    public Identifier name() {
        return name;
    }

    public ReceiverParam receiver() {
        return receiver;
    }

    public ParamDecl[] parameters() {
        return parameters;
    }

    public TypeNode[] thrown() {
        return thrown;
    }

    public Block body() {
        return body;
    }

    public MethodSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        for (Node __c : typeParams) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, returnType, offset);
        deepest = deeper(deepest, name, offset);
        deepest = deeper(deepest, receiver, offset);
        for (Node __c : parameters) deepest = deeper(deepest, __c, offset);
        for (Node __c : thrown) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitMethodDecl(this);
    }
}
