/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class CatchClause extends Node {
    private final ParamDecl parameter;
    private final Block body;
    public CatchClause(ParamDecl parameter, Block body, SourceRange range) {
        super(range);
        this.parameter = parameter;
        this.body = body;
        if (parameter != null) parameter.setParent(this);
        if (body != null) body.setParent(this);
    }
    public ParamDecl parameter() { return parameter; }
    public Block body() { return body; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, parameter, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitCatchClause(this); }
}
