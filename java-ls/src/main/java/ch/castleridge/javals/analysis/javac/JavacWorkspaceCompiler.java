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
package ch.castleridge.javals.analysis.javac;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemorySource;
import ch.castleridge.javals.classpath.ClasspathOrder;

/**
 * Compiles a single Java source under a {@link JavacTask} whose file
 * manager is an {@link IndexFileManager} layered on top of a standard
 * file manager and whose {@code ClassReader} is swapped out for an
 * {@link IndexClassReader}.
 */
public final class JavacWorkspaceCompiler implements WorkspaceCompiler {

    private static final String OBJECT_JVM_NAME = "java/lang/Object";

    /**
     * Types are resolved through {@link IndexFileManager}, never through the
     * server's own classpath. Saying so explicitly also keeps
     * {@code BasicJavacTask.initPlugins} from building a fresh
     * {@code URLClassLoader} over {@code CLASS_PATH} for every task, which
     * re-opens and re-parses the manifest of every classpath jar.
     */
    private static final List<String> TASK_OPTIONS = List.of("-proc:none", "-classpath", "");

    private final SymbolLocator symbolLocator;
    private final Map<String, String> sourceJarByBinaryJar;

    public JavacWorkspaceCompiler() {
        this(new SymbolLocator(new SourceCache()), Map.of());
    }

    public JavacWorkspaceCompiler(SymbolLocator symbolLocator, Map<String, String> sourceJarByBinaryJar) {
        this.symbolLocator = symbolLocator;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    @Override
    public AnalysisSession analyze(URI uri, CharSequence text, Index index, ClasspathOrder classpath) {
        Result result = compile(uri, text, index, classpath);
        String docUri = uri == null ? "" : uri.toString();
        return new JavacAnalysisSession(result, docUri, symbolLocator, sourceJarByBinaryJar, index, classpath);
    }

    /**
     * Returned from {@link #compile(URI, CharSequence, Index, ClasspathOrder)}.
     * The {@link CompilationUnitTree} is the parsed-and-analysed view of
     * the input; {@link Trees} is bound to {@link #task} and is the
     * cheapest way to get back to {@link javax.lang.model.element.Element}
     * instances and source positions.
     */
    public record Result(JavacTask task,
                         CompilationUnitTree cu,
                         Trees trees,
                         JavaFileObject source,
                         List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    /**
     * Compile {@code text} as if it lived at {@code uri}. Diagnostics are
     * swallowed - the LSP handlers only need bound symbols, not the
     * diagnostic list. {@code task.analyze()} is invoked so identifiers
     * inside the CU are resolved to their declarations.
     */
    public static Result compile(URI uri, CharSequence text, Index index, ClasspathOrder classpath) {
        // Without java/lang/Object in the index javac cannot establish the
        // root of the type hierarchy and every name resolution fails. Bail
        // out before standing up the task rather than producing a flood of
        // misleading "cannot find symbol" diagnostics.
        if (!index.contains(OBJECT_JVM_NAME)) {
            JavaFileObject input = new InMemorySource(uri, text);
            return new Result(null, null, null, input, List.of());
        }

        JavacTool tool = JavacTool.create();
        Context ctx = new Context();
        IndexClassReader.preRegister(ctx, index, classpath);

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        StandardJavaFileManager std = tool.getStandardFileManager(
                d -> {}, Locale.ROOT, StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, classpath);

        JavaFileObject input = new InMemorySource(uri, text);

        JavacTask task = (JavacTask) tool.getTask(
                null,
                fm,
                collector,
                TASK_OPTIONS,
                List.of(),
                List.of(input),
                ctx);

        try {
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            CompilationUnitTree cu = firstOrNull(parsed);
            try {
                task.analyze();
            } catch (RuntimeException | Error e) {
                throw compileFailed(uri, e, collector);
            }
            return new Result(task, cu, Trees.instance(task), input, List.copyOf(collector.getDiagnostics()));
        } catch (IOException e) {
            return new Result(task, null, Trees.instance(task), input, List.copyOf(collector.getDiagnostics()));
        }
    }

    private static RuntimeException compileFailed(URI uri,
                                                  Throwable cause,
                                                  DiagnosticCollector<JavaFileObject> collector) {
        StringBuilder message = new StringBuilder("Compile failed for ").append(uri);
        List<Diagnostic<? extends JavaFileObject>> diagnostics = collector.getDiagnostics();
        if (!diagnostics.isEmpty()) {
            message.append(" (").append(diagnostics.size()).append(" javac diagnostic(s) before failure)");
            int limit = Math.min(3, diagnostics.size());
            for (int i = 0; i < limit; i++) {
                Diagnostic<? extends JavaFileObject> d = diagnostics.get(i);
                message.append("\n  - ").append(d);
            }
            if (diagnostics.size() > limit) {
                message.append("\n  - ... ").append(diagnostics.size() - limit).append(" more");
            }
        }
        message.append("\nCaused by: ").append(cause);
        return new RuntimeException(message.toString(), cause);
    }

    private static <T> T firstOrNull(Iterable<? extends T> it) {
        Iterator<? extends T> i = it.iterator();
        return i.hasNext() ? i.next() : null;
    }

}
