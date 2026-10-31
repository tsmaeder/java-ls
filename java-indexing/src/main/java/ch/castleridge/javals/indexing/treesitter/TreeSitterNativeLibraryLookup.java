/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;

/**
 * Points jtreesitter at the provisioned {@code tree-sitter} shared library.
 */
public final class TreeSitterNativeLibraryLookup implements NativeLibraryLookup {

    @Override
    public SymbolLookup get(Arena arena) {
        Optional<Path> dir = TreeSitterNatives.directory();
        if (dir.isEmpty()) {
            return name -> Optional.empty();
        }
        Path lib = dir.get().resolve(System.mapLibraryName("tree-sitter"));
        try {
            return SymbolLookup.libraryLookup(lib, arena);
        } catch (IllegalArgumentException e) {
            return name -> Optional.empty();
        }
    }
}
