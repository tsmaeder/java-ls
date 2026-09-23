/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TypeHierarchyItem;

import ch.castleridge.javals.ast.AstVisitor;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.ast.TypeDeclKind;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Compiler-neutral {@link AnalysisSession} over a lowered {@link CompilationUnit}.
 */
public final class AstAnalysisSession implements AnalysisSession {

    private final CompilationUnit cu;
    private final List<PublishedDiagnostic> diagnostics;
    private final Index index;
    private final ClasspathOrder classpath;
    private final AstDeclarationLocator locator;
    private final Map<String, String> sourceJarByBinaryJar;

    public AstAnalysisSession(CompilationUnit cu,
                              List<PublishedDiagnostic> diagnostics,
                              Index index,
                              ClasspathOrder classpath,
                              AstDeclarationLocator locator,
                              Map<String, String> sourceJarByBinaryJar) {
        this.cu = cu;
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        this.locator = locator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    public static AstAnalysisSession empty() {
        return new AstAnalysisSession(
                new CompilationUnit(new SourceFile("", ""), null, List.of(), List.of(), null, SourceRange.NONE),
                List.of(), null, ClasspathOrder.UNRESTRICTED, null, Map.of());
    }

    public CompilationUnit compilationUnit() {
        return cu;
    }

    @Override
    public boolean isUsable() {
        return cu != null && cu.source() != null;
    }

    @Override
    public List<PublishedDiagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public Optional<ResolvedSymbol> resolveAt(Position position) {
        if (!isUsable()) return Optional.empty();
        int offset = AstPositions.offsetAt(cu.source(), position);
        if (offset < 0) return Optional.empty();
        Identifier ident = AstSymbols.identifierAt(cu, offset);
        if (ident == null || ident.symbol() == null) return Optional.empty();
        Optional<Location> definition = definitionOf(ident.symbol());
        return Optional.of(new AstResolvedSymbol(definition, ident.symbol()));
    }

    @Override
    public List<CompletionItem> complete(CharSequence source, Position position, Index index, ClasspathOrder classpath) {
        if (!isUsable()) return List.of();
        return AstCompleter.complete(cu, source, position, index, classpath);
    }

    @Override
    public List<Location> referencesInUnit(ResolvedSymbol symbol) {
        if (!isUsable() || symbol == null) return List.of();
        Symbol ast = astSymbol(symbol);
        if (ast != null && ast.fileLocal()) return locationsMatching(ast, null);
        return findReferencesTo(symbol.key());
    }

    @Override
    public List<Location> findReferencesTo(SymbolKey key) {
        if (!isUsable() || key == null || key.fileLocal()) return List.of();
        return locationsMatching(null, key);
    }

    @Override
    public Optional<Location> definitionOf(ResolvedSymbol symbol) {
        if (symbol == null) return Optional.empty();
        if (symbol.definition().isPresent()) return symbol.definition();
        return definitionOf(astSymbol(symbol));
    }

    @Override
    public Optional<TypeHierarchyItem> prepareTypeHierarchy(Position position) {
        Optional<ResolvedSymbol> resolved = resolveAt(position);
        if (resolved.isEmpty() || !(resolved.get() instanceof AstResolvedSymbol ast)
                || !(ast.symbol() instanceof TypeSymbol type)) {
            return Optional.empty();
        }
        Optional<Location> location = definitionOf(ast);
        if (location.isEmpty()) return Optional.empty();
        return TypeHierarchySupport.itemForResolved(ast, location.get(), symbolKind(type.kind()));
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySupertypes(TypeHierarchyItem item) {
        if (index == null) return List.of();
        return TypeHierarchySupport.directSupertypes(item, index, classpath, this::locateTypeEntry);
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySubtypes(TypeHierarchyItem item) {
        if (index == null) return List.of();
        return TypeHierarchySupport.directSubtypes(item, index, classpath, this::locateTypeEntry);
    }

    private Optional<Location> locateTypeEntry(TypeEntry entry) {
        if (locator == null) return Optional.empty();
        return locator.locateType(entry, sourceJarByBinaryJar);
    }

    private Optional<Location> definitionOf(Symbol symbol) {
        if (symbol == null) return Optional.empty();
        Identifier inFile = declarationIdent(symbol);
        if (inFile != null && inFile.range().isPresent() && AstSymbols.isDeclarationName(inFile)) {
            return Optional.of(AstPositions.location(cu.uri(), cu.source(), inFile.range()));
        }
        TypeEntry owner = ownerEntry(symbol);
        if (owner == null || locator == null) return Optional.empty();
        return locator.locate(symbol, owner, sourceJarByBinaryJar);
    }

    private List<Location> locationsMatching(Symbol exact, SymbolKey key) {
        Set<Location> found = new LinkedHashSet<>();
        cu.accept(new AstVisitor() {
            @Override
            public void visitIdentifier(Identifier n) {
                if (n.symbol() == null) {
                    visitChildren(n);
                    return;
                }
                boolean match = exact != null
                        ? n.symbol() == exact
                        : key != null && key.matches(n.symbol().key());
                if (match && n.range().isPresent()) {
                    found.add(AstPositions.location(cu.uri(), cu.source(), n.range()));
                }
                visitChildren(n);
            }
        });
        return List.copyOf(found);
    }

    private Identifier declarationIdent(Symbol symbol) {
        Identifier[] found = new Identifier[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitIdentifier(Identifier n) {
                if (found[0] == null && n.symbol() == symbol && AstSymbols.isDeclarationName(n)) {
                    found[0] = n;
                }
                visitChildren(n);
            }
        });
        return found[0];
    }

    private TypeEntry ownerEntry(Symbol symbol) {
        if (index == null) return null;
        TypeSymbol owner = owner(symbol);
        if (owner == null) return null;
        String jvm = owner.jvmBinaryName().replace('.', '/');
        return classpath.pick(index.getAll(jvm), TypeEntry::sourceUri);
    }

    private static TypeSymbol owner(Symbol symbol) {
        return switch (symbol) {
            case TypeSymbol type -> type;
            case ch.castleridge.javals.ast.MethodSymbol method -> method.owner();
            case ch.castleridge.javals.ast.FieldSymbol field -> field.owner();
            case ch.castleridge.javals.ast.EnumConstantSymbol constant -> constant.owner();
            case ch.castleridge.javals.ast.RecordComponentSymbol component -> component.owner();
            default -> null;
        };
    }

    private static Symbol astSymbol(ResolvedSymbol symbol) {
        return symbol instanceof AstResolvedSymbol ast ? ast.symbol() : null;
    }

    private static SymbolKind symbolKind(TypeDeclKind kind) {
        return switch (kind) {
            case INTERFACE, ANNOTATION -> SymbolKind.Interface;
            case ENUM -> SymbolKind.Enum;
            default -> SymbolKind.Class;
        };
    }
}
