/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class RecordPattern extends Pattern {
    private final TypeNode type;
    private final Pattern[] nested;
    public RecordPattern(TypeNode type, Pattern[] nested, SourceRange range) {
        super(range);
        this.type = type;
        this.nested = EmptyArrays.orEmpty(nested, EmptyArrays.PATTERN);
        if (type != null) type.setParent(this);
        for (Node __c : this.nested) __c.setParent(this);
    }
    public TypeNode type() { return type; }
    public Pattern[] nested() { return nested; }
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
