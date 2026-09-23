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
    }
    public TypeNode type() { return type; }
    public List<Pattern> nested() { return nested; }
    @Override public List<? extends Node> children() { return kids(type, nested); }
    @Override public void accept(AstVisitor visitor) { visitor.visitRecordPattern(this); }
}
