/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class SwitchArm extends Node {
    private final List<CaseLabel> labels;
    private final boolean arrow;
    private final Expression expressionBody;
    private final List<Statement> statements;
    public SwitchArm(List<CaseLabel> labels, boolean arrow, Expression expressionBody,
                     List<Statement> statements, SourceRange range) {
        super(range);
        this.labels = labels == null ? List.of() : List.copyOf(labels);
        this.arrow = arrow;
        this.expressionBody = expressionBody;
        this.statements = statements == null ? List.of() : List.copyOf(statements);
    }
    public List<CaseLabel> labels() { return labels; }
    public boolean arrow() { return arrow; }
    public Expression expressionBody() { return expressionBody; }
    public List<Statement> statements() { return statements; }
    @Override public List<? extends Node> children() { return kids(labels, expressionBody, statements); }
    @Override public void accept(AstVisitor visitor) { visitor.visitSwitchArm(this); }
}
