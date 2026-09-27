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
        for (Node __c : this.init) __c.setParent(this);
        if (condition != null) condition.setParent(this);
        for (Node __c : this.update) __c.setParent(this);
        if (body != null) body.setParent(this);
    }
    public List<Node> init() { return init; }
    public Expression condition() { return condition; }
    public List<Expression> update() { return update; }
    public Statement body() { return body; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : init) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, condition, offset);
        for (Node __c : update) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, body, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitForStmt(this); }
}
