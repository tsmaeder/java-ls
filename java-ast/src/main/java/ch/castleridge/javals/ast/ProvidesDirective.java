/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class ProvidesDirective extends ModuleDirective {

    private final TypeName service;
    private final List<TypeName> implementations;

    public ProvidesDirective(TypeName service, List<TypeName> implementations, SourceRange range) {
        super(range);
        this.service = service;
        this.implementations = implementations == null ? List.of() : List.copyOf(implementations);
    }

    public TypeName service() {
        return service;
    }

    public List<TypeName> implementations() {
        return implementations;
    }

    @Override
    public List<? extends Node> children() {
        return kids(service, implementations);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitProvidesDirective(this);
    }
}
