/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class LabeledStmt extends Statement {
    private final Identifier label;
    private final Statement statement;
    public LabeledStmt(Identifier label, Statement statement, SourceRange range) {
        super(range);
        this.label = label;
        this.statement = statement;
    }
    public Identifier label() { return label; }
    public Statement statement() { return statement; }
    @Override public List<? extends Node> children() { return kids(label, statement); }
    @Override public void accept(AstVisitor visitor) { visitor.visitLabeledStmt(this); }
}
