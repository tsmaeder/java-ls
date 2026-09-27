/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class RecordPattern extends Pattern {
    private final TypeNode type;
    private final List<Pattern> nested;
    public RecordPattern(TypeNode type, List<Pattern> nested, SourceRange range) {
        super(range);
        this.type = type;
        this.nested = nested == null ? List.of() : List.copyOf(nested);
        if (type != null) type.setParent(this);
        for (Node __c : this.nested) __c.setParent(this);
    }
    public TypeNode type() { return type; }
    public List<Pattern> nested() { return nested; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, type, offset);
        for (Node __c : nested) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitRecordPattern(this); }
}
