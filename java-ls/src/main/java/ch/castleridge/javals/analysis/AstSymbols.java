/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import ch.castleridge.javals.ast.AstVisitor;
import ch.castleridge.javals.ast.CompactConstructorDecl;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.EnumConstantDecl;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.Node;
import ch.castleridge.javals.ast.ParamDecl;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.ast.TypeParamDecl;
import ch.castleridge.javals.ast.VarFragment;

final class AstSymbols {

    private AstSymbols() {}

    static Identifier identifierAt(CompilationUnit cu, int offset) {
        if (cu == null || offset < 0) return null;
        Identifier[] best = new Identifier[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitIdentifier(Identifier n) {
                if (n.symbol() != null && hits(n, offset)) {
                    if (best[0] == null || better(n, best[0], offset)) {
                        best[0] = n;
                    }
                }
                visitChildren(n);
            }
        });
        return best[0];
    }

    static boolean isDeclarationName(Identifier ident) {
        if (ident == null) return false;
        Node parent = ident.parent();
        return switch (parent) {
            case TypeDecl type -> type.name() == ident;
            case MethodDecl method -> method.name() == ident;
            case ConstructorDecl ctor -> ctor.name() == ident;
            case CompactConstructorDecl compact -> compact.name() == ident;
            case VarFragment fragment -> fragment.name() == ident;
            case ParamDecl param -> param.name() == ident;
            case EnumConstantDecl constant -> constant.name() == ident;
            case RecordComponentDecl component -> component.name() == ident;
            case TypeParamDecl typeParam -> typeParam.name() == ident;
            case null, default -> false;
        };
    }

    private static boolean hits(Identifier ident, int offset) {
        if (covers(ident.range(), offset)) return true;
        if (ident.range().isPresent()) return false;
        Node parent = ident.parent();
        if (parent instanceof ch.castleridge.javals.ast.Select
                || parent instanceof ch.castleridge.javals.ast.CallExpr) {
            return false;
        }
        return parentCovers(ident, offset);
    }

    private static boolean better(Identifier candidate, Identifier current, int offset) {
        boolean candidateOwn = covers(candidate.range(), offset);
        boolean currentOwn = covers(current.range(), offset);
        if (candidateOwn != currentOwn) return candidateOwn;
        return span(candidate.range()) < span(current.range());
    }

    private static boolean parentCovers(Identifier ident, int offset) {
        Node parent = ident.parent();
        return parent != null && covers(parent.range(), offset);
    }

    static boolean covers(SourceRange range, int offset) {
        if (range == null || !range.isPresent()) return false;
        if (offset >= range.start() && offset < range.end()) return true;
        return offset > 0 && offset - 1 >= range.start() && offset - 1 < range.end();
    }

    static int span(SourceRange range) {
        return range == null || !range.isPresent() ? Integer.MAX_VALUE : range.end() - range.start();
    }
}
