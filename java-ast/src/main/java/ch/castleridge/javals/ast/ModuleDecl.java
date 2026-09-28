/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;


public final class ModuleDecl extends Node {

    private final boolean open;
    private final Annotation[] annotations;
    private final Identifier[] names;
    private final ModuleDirective[] directives;
    private final ModuleSymbol symbol;

    public ModuleDecl(boolean open,
                      Annotation[] annotations,
                      Identifier[] names,
                      ModuleDirective[] directives,
                      ModuleSymbol symbol,
                      SourceRange range) {
        super(range);
        this.open = open;
        this.annotations = EmptyArrays.orEmpty(annotations, EmptyArrays.ANNOTATION);
        this.names = EmptyArrays.orEmpty(names, EmptyArrays.IDENTIFIER);
        this.directives = EmptyArrays.orEmpty(directives, EmptyArrays.MODULE_DIRECTIVE);
        this.symbol = symbol;
        for (Node __c : this.annotations) __c.setParent(this);
        for (Node __c : this.names) __c.setParent(this);
        for (Node __c : this.directives) __c.setParent(this);
    }

    public boolean open() {
        return open;
    }

    public Annotation[] annotations() {
        return annotations;
    }

    public Identifier[] names() {
        return names;
    }

    public ModuleDirective[] directives() {
        return directives;
    }

    public ModuleSymbol symbol() {
        return symbol;
    }
    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        for (Node __c : annotations) deepest = deeper(deepest, __c, offset);
        for (Node __c : names) deepest = deeper(deepest, __c, offset);
        for (Node __c : directives) deepest = deeper(deepest, __c, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitModuleDecl(this);
    }
}
