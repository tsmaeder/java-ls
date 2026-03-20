package ch.castleridge.javals.indexing;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Indexer {
    public static void main(String[] args) throws IOException {
        boolean isolate = true;
        int i = 0;
        while (i < args.length && args[i].startsWith("--")) {
            if ("--isolate".equals(args[i])) {
                isolate = true;
            } else {
                System.err.println("Unknown option: " + args[i]);
                System.err.println("Usage: Indexer [--isolate] <path-to-java-file>");
                System.exit(1);
            }
            i++;
        }
        if (i >= args.length) {
            System.err.println("Usage: Indexer [--isolate] <path-to-java-file>");
            System.exit(1);
        }
        Path path = Paths.get(args[i]);
        new Indexer().index(path, isolate);
    }

    public void index(Path path) throws IOException {
        index(path, false);
    }

    public void index(Path path, boolean isolate) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        index(path, isolate, diagnostics, false, true);
    }

    /**
     * Same parse/analyze pipeline as {@link #index(Path, boolean)} without printing diagnostics or
     * running the call-site scanner (for tests).
     *
     * @return false if javac aborted analysis (should be rare in {@code isolate} mode now that
     *     {@link IsolatingJavaFileManager} exposes {@code java.lang}), true if
     *     {@link JavacTask#analyze()} returned normally
     */
    boolean compileOnly(Path path, boolean isolate, DiagnosticCollector<JavaFileObject> diagnostics)
            throws IOException {
        return index(path, isolate, diagnostics, false, false);
    }

    private boolean index(
            Path path,
            boolean isolate,
            DiagnosticCollector<JavaFileObject> diagnostics,
            boolean printDiagnostics,
            boolean runScanner)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Not a regular file: " + path);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available (use a JDK, not a JRE-only runtime).");
        }

        List<String> options = List.of("-classpath", "", "-proc:none");

        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaFileManager fileManager =
                    isolate ? new IsolatingJavaFileManager(standard) : standard;
            Iterable<? extends JavaFileObject> sources =
                    standard.getJavaFileObjects(path.toAbsolutePath().normalize().toFile());
            CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, sources);
            if (!(task instanceof JavacTask javacTask)) {
                throw new IllegalStateException("Compiler task is not a JavacTask; unsupported JDK.");
            }

            Iterable<? extends CompilationUnitTree> units = javacTask.parse();
            boolean analyzed = analyzeAllowingJavacAbort(javacTask);

            if (printDiagnostics) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    System.err.println(d);
                }
            }

            if (runScanner && analyzed) {
                Trees trees = Trees.instance(javacTask);
                CallSiteScanner scanner = new CallSiteScanner(trees);
                for (CompilationUnitTree unit : units) {
                    scanner.scan(new TreePath(unit), null);
                }
            }
            return analyzed;
        }
    }

    /**
     * When the file manager hides too much of the JDK, javac can abort while resolving symbols
     * instead of emitting ordinary ERROR diagnostics.
     */
    private static boolean analyzeAllowingJavacAbort(JavacTask javacTask) throws IOException {
        try {
            javacTask.analyze();
            return true;
        } catch (IllegalStateException e) {
            if (isJavacAbort(e)) {
                return false;
            }
            throw e;
        }
    }

    private static boolean isJavacAbort(IllegalStateException e) {
        Throwable c = e.getCause();
        return c != null && "com.sun.tools.javac.util.Abort".equals(c.getClass().getName());
    }

    private static final class CallSiteScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;

        CallSiteScanner(Trees trees) {
            this.trees = trees;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            TreePath path = new TreePath(getCurrentPath(), node);
            emit(methodName(node), path);
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            TreePath path = new TreePath(getCurrentPath(), node);
            emit(constructorName(node), path);
            return super.visitNewClass(node, unused);
        }

        private void emit(String name, TreePath path) {
            Element element = trees.getElement(path);
            String symbol =
                    element instanceof ExecutableElement executable ? executable.toString() : "external";
            System.out.println(name + "\t" + symbol);
        }

        private static String methodName(MethodInvocationTree tree) {
            ExpressionTree select = tree.getMethodSelect();
            if (select instanceof IdentifierTree id) {
                return id.getName().toString();
            }
            if (select instanceof MemberSelectTree ms) {
                Object tail = ms.getIdentifier();
                if (tail instanceof IdentifierTree ident) {
                    return ident.getName().toString();
                }
                if (tail instanceof Name n) {
                    return n.toString();
                }
                return String.valueOf(tail);
            }
            return select != null ? select.toString() : "<complex>";
        }

        private static String constructorName(NewClassTree tree) {
            ExpressionTree id = tree.getIdentifier();
            if (id instanceof IdentifierTree ident) {
                return ident.getName().toString();
            }
            if (id != null) {
                return id.toString();
            }
            return "<init>";
        }
    }
}
