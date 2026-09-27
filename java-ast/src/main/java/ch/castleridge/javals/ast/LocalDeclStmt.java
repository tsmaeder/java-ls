/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.util.Set;

public final class LocalDeclStmt extends Statement {
    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final TypeNode type;
    private final List<VarFragment> fragments;
    public LocalDeclStmt(Set<Modifier> modifiers, List<Annotation> annotations, TypeNode type,
                         List<VarFragment> fragments, SourceRange range) {
        super(range);
        this.modifiers = MethodDecl.copyMods(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.type = type;
        this.fragments = fragments == null ? List.of() : List.copyOf(fragments);
        for (Node __c : this.annotations) __c.setParent(this);
        if (type != null) type.setParent(this);
        for (Node __c : this.fragments) __c.setParent(this);
    }
    public Set<Modifier> modifiers() { return modifiers; }
    public List<Annotation> annotations() { return annotations; }
    public TypeNode type() { return type; }
    public List<VarFragment> fragments() { return fragments; }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, type, offset);
        for (Node __c : fragments) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) { visitor.visitLocalDeclStmt(this); }
}
