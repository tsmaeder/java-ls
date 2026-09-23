/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class CatchClause extends Node {
    private final ParamDecl parameter;
    private final Block body;
    public CatchClause(ParamDecl parameter, Block body, SourceRange range) {
        super(range);
        this.parameter = parameter;
        this.body = body;
    }
    public ParamDecl parameter() { return parameter; }
    public Block body() { return body; }
    @Override public List<? extends Node> children() { return kids(parameter, body); }
    @Override public void accept(AstVisitor visitor) { visitor.visitCatchClause(this); }
}
