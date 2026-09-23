/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ForStmt extends Statement {
    private final List<Node> init;
    private final Expression condition;
    private final List<Expression> update;
    private final Statement body;
    public ForStmt(List<Node> init, Expression condition, List<Expression> update, Statement body, SourceRange range) {
        super(range);
        this.init = init == null ? List.of() : List.copyOf(init);
        this.condition = condition;
        this.update = update == null ? List.of() : List.copyOf(update);
        this.body = body;
    }
    public List<Node> init() { return init; }
    public Expression condition() { return condition; }
    public List<Expression> update() { return update; }
    public Statement body() { return body; }
    @Override public List<? extends Node> children() { return kids(init, condition, update, body); }
    @Override public void accept(AstVisitor visitor) { visitor.visitForStmt(this); }
}
