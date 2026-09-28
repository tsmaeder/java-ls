/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class PackageDecl extends Declaration {

    private final Annotation[] annotations;
    private final Identifier[] names;

    public PackageDecl(Annotation[] annotations, Identifier[] names, SourceRange range) {
        super(range);
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.names = EmptyArrays.orEmpty(names, EmptyArrays.IDENTIFIER);
        for (Node __c : this.annotations) __c.setParent(this);
        for (Node __c : this.names) __c.setParent(this);
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public Identifier[] names() {
        return names;
    }

    public String qualifiedName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(names[i].name());
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
