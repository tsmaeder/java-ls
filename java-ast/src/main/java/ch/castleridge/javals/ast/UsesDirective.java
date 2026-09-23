/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class UsesDirective extends ModuleDirective {

    private final TypeName service;

    public UsesDirective(TypeName service, SourceRange range) {
        super(range);
        this.service = service;
    }

    public TypeName service() {
        return service;
    }

    @Override
    public List<? extends Node> children() {
        return kids(service);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitUsesDirective(this);
    }
}
