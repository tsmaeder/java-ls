/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class RecordComponentDecl extends Declaration {

    private final List<Annotation> annotations;
    private final TypeNode type;
    private final Identifier name;
    private final boolean varargs;
    private final RecordComponentSymbol symbol;

    public RecordComponentDecl(List<Annotation> annotations,
                               TypeNode type,
                               Identifier name,
                               boolean varargs,
                               RecordComponentSymbol symbol,
                               SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.type = type;
        this.name = name;
        this.varargs = varargs;
        this.symbol = symbol;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public TypeNode type() {
        return type;
    }

    public Identifier name() {
        return name;
    }

    public boolean varargs() {
        return varargs;
    }

    public RecordComponentSymbol symbol() {
        return symbol;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, type, name);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitRecordComponentDecl(this);
    }
}
