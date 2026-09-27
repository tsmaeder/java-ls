/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.util.Set;

public final class ParamDecl extends Declaration {

    private final Set<Modifier> modifiers;
    private final List<Annotation> annotations;
    private final TypeNode type;
    private final Identifier name;
    private final boolean varargs;
    private final LocalSymbol symbol;

    public ParamDecl(Set<Modifier> modifiers,
                     List<Annotation> annotations,
                     TypeNode type,
                     Identifier name,
                     boolean varargs,
                     LocalSymbol symbol,
                     SourceRange range) {
        super(range);
        this.modifiers = MethodDecl.copyMods(modifiers);
        this.annotations = annotations == null ? List.of() : List.copyOf(annotations);
        this.type = type;
        this.name = name;
        this.varargs = varargs;
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        if (type != null) type.setParent(this);
        if (name != null) name.setParent(this);
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

    public Identifier name() {
        return name;
    }

    public boolean varargs() {
        return varargs;
    }

    public boolean unnamed() {
        return name != null && "_".equals(name.name());
    }

    public LocalSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        deepest = deeper(deepest, type, offset);
        deepest = deeper(deepest, name, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitParamDecl(this);
    }
}
