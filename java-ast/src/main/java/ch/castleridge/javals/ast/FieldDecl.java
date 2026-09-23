/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.util.Set;

public final class FieldDecl extends Declaration {

    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final TypeNode type;
    private final List<VarFragment> fragments;

    public FieldDecl(Set<Modifier> modifiers,
                     List<Annotation> annotations,
                     TypeNode type,
                     List<VarFragment> fragments,
                     SourceRange range) {
        super(range);
        this.modifiers = MethodDecl.copyMods(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.type = type;
        this.fragments = fragments == null ? List.of() : List.copyOf(fragments);
    }

    public Set<Modifier> modifiers() {
        return modifiers;
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public TypeNode type() {
        return type;
    }

    public List<VarFragment> fragments() {
        return fragments;
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, type, fragments);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitFieldDecl(this);
    }
}
