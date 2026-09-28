/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ForEachStmt extends Statement {
    private final LocalDeclStmt variable;
    private final Expression iterable;
    private final Statement body;
    public ForEachStmt(LocalDeclStmt variable, Expression iterable, Statement body, SourceRange range) {
        super(range);
        this.variable = variable;
        this.iterable = iterable;
        this.body = body;
        if (variable != null) variable.setParent(this);
        if (iterable != null) iterable.setParent(this);
        if (body != null) body.setParent(this);
    }
    public LocalDeclStmt variable() { return variable; }
    public Expression iterable() { return iterable; }
    public Statement body() { return body; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, variable, offset);
        deepest = deeper(deepest, iterable, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitForEachStmt(this); }
}
