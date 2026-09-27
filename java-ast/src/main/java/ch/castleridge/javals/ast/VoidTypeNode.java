/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class VoidTypeNode extends TypeNode {

    public VoidTypeNode(JType resolved, SourceRange range) {
        super(range, coalesce(resolved, JType.VOID));
    }
    @Override
    public Node nodeAt(int offset) {
        return covers(offset) ? this : null;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitVoidTypeNode(this);
    }
}
