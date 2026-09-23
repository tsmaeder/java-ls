/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;
import java.net.URI;

public final class CompilationUnit extends Node {

    private final SourceFile source;
    private final PackageDecl packageDecl;
    private final List<ImportDecl> imports;
    private final List<TypeDecl> types;
    private final ModuleDecl module;

    public CompilationUnit(SourceFile source,
                           PackageDecl packageDecl,
                           List<ImportDecl> imports,
                           List<TypeDecl> types,
                           ModuleDecl module,
                           SourceRange range) {
        super(range);
        this.source = source == null ? new SourceFile("", "") : source;
        this.packageDecl = packageDecl;
        this.imports = imports == null ? List.of() : List.copyOf(imports);
        this.types = types == null ? List.of() : List.copyOf(types);
        this.module = module;
        attach(this, null);
        freeze();
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

    public List<ImportDecl> imports() {
        return imports;
    }

    public List<TypeDecl> types() {
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
    public List<? extends Node> children() {
        return kids(packageDecl, imports, types, module);
    }

    @Override
    public void accept(AstVisitor visitor) {
        visitor.visitCompilationUnit(this);
    }
}
