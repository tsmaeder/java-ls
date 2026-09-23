/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class LambdaExpr extends Expression {

    private final List<ParamDecl> parameters;
    private final boolean implicitParameters;
    private final Expression expressionBody;
    private final Block blockBody;

    public LambdaExpr(List<ParamDecl> parameters,
                      boolean implicitParameters,
                      Expression expressionBody,
                      Block blockBody,
                      SourceRange range) {
        super(range);
        this.parameters = parameters == null ? List.of() : List.copyOf(parameters);
        this.implicitParameters = implicitParameters;
        this.expressionBody = expressionBody;
        this.blockBody = blockBody;
    }

    public List<ParamDecl> parameters() {
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
    public List<? extends Node> children() {
        return kids(parameters, expressionBody, blockBody);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitLambdaExpr(this);
    }
}
