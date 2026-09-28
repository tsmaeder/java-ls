/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.net.URI;

public final class CompilationUnit extends Node {

    private final SourceFile source;
    private final PackageDecl packageDecl;
    private final ImportDecl[] imports;
    private final TypeDecl[] types;
    private final ModuleDecl module;

    public CompilationUnit(SourceFile source,
                           PackageDecl packageDecl,
                           ImportDecl[] imports,
                           TypeDecl[] types,
                           ModuleDecl module,
                           SourceRange range) {
        super(range);
        this.source = source == null ? new SourceFile("", "") : source;
        this.packageDecl = packageDecl;
        this.imports = EmptyArrays.orEmpty(imports, EmptyArrays.IMPORT_DECL);
        this.types = EmptyArrays.orEmpty(types, EmptyArrays.TYPE_DECL);
        this.module = module;
        if (packageDecl != null) packageDecl.setParent(this);
        for (Node __c : this.imports) __c.setParent(this);
        for (Node __c : this.types) __c.setParent(this);
        if (module != null) module.setParent(this);
    }

    public SourceFile source() {
        return source;
    }

    public String uri() {
        return source.uri();
    }

    public PackageDecl packageDecl() {
        return packageDecl;
    }

    public ImportDecl[] imports() {
        return imports;
    }

    public TypeDecl[] types() {
        return types;
    }

    public ModuleDecl module() {
        return module;
    }

    public String slice(Node node) {
        return source.slice(node);
    }

    public Node nodeAt(URI ignored, int offset) {
        return nodeAt(offset);
    }

    @Override
    public Node nodeAt(int offset) {
        if (!covers(offset)) return null;
        Node deepest = this;
        deepest = deeper(deepest, packageDecl, offset);
        for (ImportDecl imp : imports) deepest = deeper(deepest, imp, offset);
        for (TypeDecl type : types) deepest = deeper(deepest, type, offset);
        deepest = deeper(deepest, module, offset);
        return deepest;
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCompilationUnit(this);
    }
}
