/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ProvidesDirective extends ModuleDirective {

    private final TypeName service;
    private final TypeName[] implementations;

    public ProvidesDirective(TypeName service, TypeName[] implementations, SourceRange range) {
        super(range);
        this.service = service;
        this.implementations = EmptyArrays.orEmpty(implementations, EmptyArrays.TYPE_NAME);
        if (service != null) service.setParent(this);
        for (Node __c : this.implementations) __c.setParent(this);
    }

    public TypeName service() {
        return service;
    }

    public TypeName[] implementations() {
        return implementations;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, service, offset);
        for (Node __c : implementations) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitProvidesDirective(this);
    }
}
