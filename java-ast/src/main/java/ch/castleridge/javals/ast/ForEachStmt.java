/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ForEachStmt extends Statement {
    private final LocalDeclStmt variable;
    private final Expression iterable;
    private final Statement body;
    public ForEachStmt(LocalDeclStmt variable, Expression iterable, Statement body, SourceRange range) {
        super(range);
        this.variable = variable;
        this.iterable = iterable;
        this.body = body;
    }
    public LocalDeclStmt variable() { return variable; }
    public Expression iterable() { return iterable; }
    public Statement body() { return body; }
    @Override public List<? extends Node> children() { return kids(variable, iterable, body); }
    @Override public void accept(AstVisitor visitor) { visitor.visitForEachStmt(this); }
}
