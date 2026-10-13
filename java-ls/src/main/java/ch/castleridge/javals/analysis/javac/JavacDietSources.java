/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;

import ch.castleridge.javals.analysis.SourceText;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.indexing.index.InMemorySource;

/**
 * Diet-parses attached {@code .java} with javac and lowers it. Used by
 * {@link ch.castleridge.javals.analysis.AstDeclarationLocator}.
 */
public final class JavacDietSources {

    private JavacDietSources() {}

    public static CompilationUnit lower(String uri) {
        String text = SourceText.read(uri);
        if (text == null) return null;
        return lower(uri, text);
    }

    public static CompilationUnit lower(String uri, CharSequence text) {
        if (text == null) return null;
        String source = text.toString();
        URI parsedUri;
        try {
            parsedUri = URI.create(uri == null || uri.isBlank() ? "memory:///Diet.java" : uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        JavacTool tool = JavacTool.create();
        StandardJavaFileManager fm = tool.getStandardFileManager(
                d -> {}, Locale.ROOT, StandardCharsets.UTF_8);
        JavaFileObject input = new InMemorySource(parsedUri, source);
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, d -> {}, List.of(), List.of(), List.of(input));
        try {
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            CompilationUnitTree cu = firstOrNull(parsed);
            if (cu == null) return null;
            Trees trees = Trees.instance(task);
            return JavacAstLowerer.diet(uri, source, cu, trees);
        } catch (IOException | RuntimeException | StackOverflowError failure) {
            return null;
        }
    }

    private static <T> T firstOrNull(Iterable<? extends T> it) {
        Iterator<? extends T> i = it.iterator();
        return i.hasNext() ? i.next() : null;
    }
}
