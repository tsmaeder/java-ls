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
        if (label != null) label.setParent(this);
        if (statement != null) statement.setParent(this);
    }
    public Identifier label() { return label; }
    public Statement statement() { return statement; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, label, offset);
        deepest = deeper(deepest, statement, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitLabeledStmt(this); }
}
