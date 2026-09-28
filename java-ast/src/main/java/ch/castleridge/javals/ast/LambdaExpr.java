/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class LambdaExpr extends Expression {

    private final ParamDecl[] parameters;
    private final boolean implicitParameters;
    private final Expression expressionBody;
    private final Block blockBody;

    public LambdaExpr(ParamDecl[] parameters,
                      boolean implicitParameters,
                      Expression expressionBody,
                      Block blockBody,
                      JType type,
                      SourceRange range) {
        super(range, type);
        this.parameters = EmptyArrays.orEmpty(parameters, EmptyArrays.PARAM_DECL);
        this.implicitParameters = implicitParameters;
        this.expressionBody = expressionBody;
        this.blockBody = blockBody;
        for (Node __c : this.parameters) __c.setParent(this);
        if (expressionBody != null) expressionBody.setParent(this);
        if (blockBody != null) blockBody.setParent(this);
    }

    public ParamDecl[] parameters() {
        return parameters;
    }

    public boolean implicitParameters() {
        return implicitParameters;
    }

    public Expression expressionBody() {
        return expressionBody;
    }

    public Block blockBody() {
        return blockBody;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : parameters) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, expressionBody, offset);
        deepest = deeper(deepest, blockBody, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitLambdaExpr(this);
    }
}
