/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public final class PackageDecl extends Declaration {

    private final List<Annotation> annotations;
    private final List<Identifier> names;

    public PackageDecl(List<Annotation> annotations, List<Identifier> names, SourceRange range) {
        super(range);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.names = names == null ? List.of() : List.copyOf(names);
    }

    public List<Annotation> annotations() {
        return annotations;
    }

    public List<Identifier> names() {
        return names;
    }

    public String qualifiedName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(names.get(i).name());
        }
        return sb.toString();
    }

    @Override
    public List<? extends Node> children() {
        return kids(annotations, names);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitPackageDecl(this);
    }
}
