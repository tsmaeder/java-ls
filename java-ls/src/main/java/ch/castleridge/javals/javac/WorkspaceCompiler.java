package ch.castleridge.javals.javac;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemorySource;

/**
 * Compiles a single Java source under a {@link JavacTask} whose file
 * manager is an {@link IndexFileManager} layered on top of a standard
 * file manager and whose {@code ClassReader} is swapped out for an
 * {@link IndexClassReader}. Mirrors the wiring in {@code Main} but
 * returns a {@link Result} that the LSP handlers can introspect.
 */
public final class WorkspaceCompiler {

    private WorkspaceCompiler() {}

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
                List.of(),
                List.of(),
                List.of(input),
                ctx);

        try {
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            CompilationUnitTree cu = firstOrNull(parsed);
            try {
                task.analyze();
            } catch (RuntimeException ignored) {
                // Analysis errors are fine; we just want as much binding as javac can give us.
            }
            return new Result(task, cu, Trees.instance(task), input, List.copyOf(collector.getDiagnostics()));
        } catch (IOException e) {
            return new Result(task, null, Trees.instance(task), input, List.copyOf(collector.getDiagnostics()));
        }
    }

    private static <T> T firstOrNull(Iterable<? extends T> it) {
        Iterator<? extends T> i = it.iterator();
        return i.hasNext() ? i.next() : null;
    }

}
