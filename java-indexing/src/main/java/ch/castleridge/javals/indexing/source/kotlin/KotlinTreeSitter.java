/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;

import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;

import ch.castleridge.javals.indexing.treesitter.TreeSitterNatives;

/**
 * Shared Kotlin tree-sitter {@link Language} plus per-thread {@link Parser}s.
 */
final class KotlinTreeSitter {

    private static final Object LOCK = new Object();
    private static volatile Language language;
    private static volatile Throwable initFailure;

    private static final ThreadLocal<Parser> PARSERS = ThreadLocal.withInitial(() -> {
        Language lang = language();
        return new Parser(lang);
    });

    private KotlinTreeSitter() {}

    static Language language() {
        Language lang = language;
        if (lang != null) return lang;
        synchronized (LOCK) {
            if (language != null) return language;
            if (initFailure != null) {
                throw new IllegalStateException("Kotlin tree-sitter failed to initialize", initFailure);
            }
            try {
                SymbolLookup symbols = SymbolLookup.libraryLookup(
                        TreeSitterNatives.kotlinLibrary(), Arena.global());
                language = Language.load(symbols, "tree_sitter_kotlin");
                return language;
            } catch (Throwable t) {
                initFailure = t;
                throw new IllegalStateException("Kotlin tree-sitter failed to initialize", t);
            }
        }
    }

    static Optional<Tree> parse(String source) {
        Parser parser = PARSERS.get();
        return parser.parse(source, InputEncoding.UTF_8);
    }
}
