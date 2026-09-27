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
        if (service != null) service.setParent(this);
    }

    public TypeName service() {
        return service;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, service, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitUsesDirective(this);
    }
}
