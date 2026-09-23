/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.Optional;

import org.eclipse.lsp4j.Location;

import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.SymbolKey;

/**
 * {@link ResolvedSymbol} wrapping an owned AST {@link Symbol}.
 */
public record AstResolvedSymbol(
        Optional<Location> definition,
        Symbol symbol) implements ResolvedSymbol {

    @Override
    public SymbolKey key() {
        SymbolKey key = symbol.key();
        return key != null ? key : SymbolKey.local(symbol.name());
    }
}
