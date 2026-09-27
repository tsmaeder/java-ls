/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class EmptyStmt extends Statement {
    public EmptyStmt(SourceRange range) { super(range); }
    @Override
    public Node nodeAt(int offset) {
        return covers(offset) ? this : null;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitEmptyStmt(this); }
}
