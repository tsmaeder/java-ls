/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;

import ch.castleridge.javals.ast.Annotation;
import ch.castleridge.javals.ast.CompactConstructorDecl;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.EnumConstantDecl;
import ch.castleridge.javals.ast.FieldDecl;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.ast.TypeDeclKind;
import ch.castleridge.javals.ast.TypeName;
import ch.castleridge.javals.ast.VarFragment;

/**
 * Hierarchical {@link DocumentSymbol} outline from a lowered {@link CompilationUnit}.
 * Structural only — works for attributed and diet ASTs alike.
 */
public final class DocumentSymbolSupport {

    private DocumentSymbolSupport() {}

    public static List<DocumentSymbol> from(CompilationUnit cu) {
        if (cu == null) return List.of();
        SourceFile source = cu.source();
        List<DocumentSymbol> roots = new ArrayList<>();
        for (TypeDecl type : cu.types()) {
            DocumentSymbol symbol = typeSymbol(type, source);
            if (symbol != null) roots.add(symbol);
        }
        return roots;
    }

    private static DocumentSymbol typeSymbol(TypeDecl type, SourceFile source) {
        if (type == null) return null;
        List<DocumentSymbol> children = new ArrayList<>();
        for (RecordComponentDecl component : type.recordComponents()) {
            DocumentSymbol child = recordComponentSymbol(component, source);
            if (child != null) children.add(child);
        }
        for (Declaration member : type.members()) {
            appendMember(children, member, source);
        }
        return symbol(
                nameOf(type.name()),
                symbolKind(type.kind()),
                type.range(),
                type.name(),
                type.annotations(),
                children,
                source);
    }

    private static void appendMember(List<DocumentSymbol> out, Declaration member, SourceFile source) {
        if (member == null) return;
        switch (member) {
            case TypeDecl nested -> {
                DocumentSymbol child = typeSymbol(nested, source);
                if (child != null) out.add(child);
            }
            case MethodDecl method -> {
                DocumentSymbol child = symbol(
                        nameOf(method.name()),
                        SymbolKind.Method,
                        method.range(),
                        method.name(),
                        method.annotations(),
                        List.of(),
                        source);
                if (child != null) out.add(child);
            }
            case ConstructorDecl ctor -> {
                DocumentSymbol child = symbol(
                        nameOf(ctor.name()),
                        SymbolKind.Constructor,
                        ctor.range(),
                        ctor.name(),
                        ctor.annotations(),
                        List.of(),
                        source);
                if (child != null) out.add(child);
            }
            case CompactConstructorDecl compact -> {
                DocumentSymbol child = symbol(
                        nameOf(compact.name()),
                        SymbolKind.Constructor,
                        compact.range(),
                        compact.name(),
                        compact.annotations(),
                        List.of(),
                        source);
                if (child != null) out.add(child);
            }
            case FieldDecl field -> {
                for (VarFragment fragment : field.fragments()) {
                    DocumentSymbol child = fieldFragmentSymbol(field, fragment, source);
                    if (child != null) out.add(child);
                }
            }
            case EnumConstantDecl constant -> {
                DocumentSymbol child = enumConstantSymbol(constant, source);
                if (child != null) out.add(child);
            }
            case RecordComponentDecl component -> {
                DocumentSymbol child = recordComponentSymbol(component, source);
                if (child != null) out.add(child);
            }
            default -> {
                // InitializerDecl and unknown members are omitted from outline.
            }
        }
    }

    private static DocumentSymbol fieldFragmentSymbol(FieldDecl field, VarFragment fragment, SourceFile source) {
        if (fragment == null) return null;
        Identifier name = fragment.name();
        SourceRange range = fragment.range() != null && fragment.range().isPresent()
                ? fragment.range()
                : field.range();
        return symbol(nameOf(name), SymbolKind.Field, range, name, field.annotations(), List.of(), source);
    }

    private static DocumentSymbol enumConstantSymbol(EnumConstantDecl constant, SourceFile source) {
        List<DocumentSymbol> children = new ArrayList<>();
        if (constant.body() != null) {
            for (Declaration member : constant.body().members()) {
                appendMember(children, member, source);
            }
        }
        return symbol(
                nameOf(constant.name()),
                SymbolKind.EnumMember,
                constant.range(),
                constant.name(),
                constant.annotations(),
                children,
                source);
    }

    private static DocumentSymbol recordComponentSymbol(RecordComponentDecl component, SourceFile source) {
        return symbol(
                nameOf(component.name()),
                SymbolKind.Field,
                component.range(),
                component.name(),
                component.annotations(),
                List.of(),
                source);
    }

    private static DocumentSymbol symbol(String name,
                                         SymbolKind kind,
                                         SourceRange declRange,
                                         Identifier nameNode,
                                         Annotation[] annotations,
                                         List<DocumentSymbol> children,
                                         SourceFile source) {
        if (name == null || name.isEmpty()) return null;
        Range range = AstPositions.rangeOf(source, declRange);
        Range selection = nameNode != null && nameNode.range().isPresent()
                ? AstPositions.rangeOf(source, nameNode.range())
                : range;
        DocumentSymbol symbol = new DocumentSymbol(name, kind, range, selection);
        if (children != null && !children.isEmpty()) {
            symbol.setChildren(children);
        }
        if (isDeprecated(annotations)) {
            symbol.setTags(List.of(SymbolTag.Deprecated));
        }
        return symbol;
    }

    private static boolean isDeprecated(Annotation[] annotations) {
        if (annotations == null) return false;
        for (Annotation annotation : annotations) {
            if (annotation == null) continue;
            TypeName typeName = annotation.name();
            if (typeName == null) continue;
            Identifier simple = typeName.simpleName();
            if (simple != null && "Deprecated".equals(simple.name())) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(Identifier name) {
        return name == null ? "" : name.name();
    }

    private static SymbolKind symbolKind(TypeDeclKind kind) {
        return switch (kind) {
            case INTERFACE, ANNOTATION -> SymbolKind.Interface;
            case ENUM -> SymbolKind.Enum;
            default -> SymbolKind.Class;
        };
    }
}
