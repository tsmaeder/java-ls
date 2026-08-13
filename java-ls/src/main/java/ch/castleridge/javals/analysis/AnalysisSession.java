/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.List;
import java.util.Optional;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TypeHierarchyItem;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * Result of analyzing a single open buffer. Feature methods are implemented
 * by the selected compiler backend (javac or ECJ).
 */
public interface AnalysisSession {

    List<PublishedDiagnostic> diagnostics();

    /**
     * Resolve the symbol at an LSP position in the analyzed source.
     */
    Optional<ResolvedSymbol> resolveAt(Position position);

    List<CompletionItem> complete(CharSequence source, Position position, Index index, ClasspathOrder classpath);

    /**
     * Find references within this unit. For file-local symbols this must be
     * the same session that produced {@code symbol}.
     */
    List<Location> referencesInUnit(ResolvedSymbol symbol);

    /**
     * Find references matching a cross-file {@link SymbolIdentity}
     * (non-file-local only).
     */
    List<Location> findReferencesTo(SymbolIdentity identity);

    Optional<Location> definitionOf(ResolvedSymbol symbol);

    /**
     * Root type hierarchy item for the type at {@code position}, if any.
     */
    Optional<TypeHierarchyItem> prepareTypeHierarchy(Position position);

    /**
     * Direct supertypes of {@code item} (extends / implements / implicit
     * Object-Enum-Record), located through attached sources when available.
     */
    List<TypeHierarchyItem> typeHierarchySupertypes(TypeHierarchyItem item);

    /**
     * Direct subtypes of {@code item} visible on this session's classpath,
     * discovered by scanning the index (and sealed {@code permits}).
     */
    List<TypeHierarchyItem> typeHierarchySubtypes(TypeHierarchyItem item);

    /**
     * True when the session has a usable attributed AST.
     */
    boolean isUsable();
}
