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

import java.util.Optional;

import org.eclipse.lsp4j.Location;

import ch.castleridge.javals.ast.SymbolKey;

/**
 * Symbol resolved at a source position. File-local symbols are only valid
 * for operations on the {@link AnalysisSession} that produced them.
 */
public interface ResolvedSymbol {

    SymbolKey key();

    Optional<Location> definition();

    default boolean fileLocal() {
        return key().fileLocal();
    }

    default String simpleName() {
        return key().simpleName();
    }

    default Optional<String> originResourceUri() {
        return key().originResourceUri();
    }
}
