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
        for (Node __c : this.annotations) __c.setParent(this);
        for (Node __c : this.names) __c.setParent(this);
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
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        for (Node __c : names) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitPackageDecl(this);
    }
}
