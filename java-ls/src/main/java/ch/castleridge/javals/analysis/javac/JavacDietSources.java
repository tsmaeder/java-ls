/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.analysis.SourceText;
import ch.castleridge.javals.ast.CompilationUnit;

/**
 * Diet-parses attached {@code .java} with javac and lowers it. Used by
 * {@link ch.castleridge.javals.analysis.AstDeclarationLocator}.
 */
public final class JavacDietSources {

    private JavacDietSources() {}

    public static CompilationUnit lower(String uri) {
        String text = SourceText.read(uri);
        if (text == null) return null;
        SourceCache cache = new SourceCache(1);
        return cache.parse(uri)
                .map(parsed -> JavacAstLowerer.diet(uri, text, parsed.cu(), parsed.trees()))
                .orElse(null);
    }
}
