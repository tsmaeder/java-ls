/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class SwitchArm extends Node {
    private final CaseLabel[] labels;
    private final boolean arrow;
    private final Expression expressionBody;
    private final Statement[] statements;
    public SwitchArm(CaseLabel[] labels, boolean arrow, Expression expressionBody,
                     Statement[] statements, SourceRange range) {
        super(range);
        this.labels = EmptyArrays.orEmpty(labels, EmptyArrays.CASE_LABEL);
        this.arrow = arrow;
        this.expressionBody = expressionBody;
        this.statements = EmptyArrays.orEmpty(statements, EmptyArrays.STATEMENT);
        for (Node __c : this.labels) __c.setParent(this);
        if (expressionBody != null) expressionBody.setParent(this);
        for (Node __c : this.statements) __c.setParent(this);
    }
    public CaseLabel[] labels() { return labels; }
    public boolean arrow() { return arrow; }
    public Expression expressionBody() { return expressionBody; }
    public Statement[] statements() { return statements; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : labels) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, expressionBody, offset);
        for (Node __c : statements) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitSwitchArm(this); }
}
