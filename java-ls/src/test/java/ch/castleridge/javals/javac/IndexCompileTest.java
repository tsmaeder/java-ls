package ch.castleridge.javals.javac;

import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

import org.junit.jupiter.api.Test;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.source.SourceIndexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexCompileTest {

    private static final String SOURCE_URI = "index:///test-classpath/";

   
    private static ClasspathOrder classPathOf(List<String> uris) {
        return new ClasspathOrder(uris.stream().map(UriClasspathEntry::of).collect(Collectors.toList()), false);
    }
 

    @Test
    void sourceReferencingIndexedClassCompilesCleanly() throws Exception {
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/String", "<init>"));    
        
        index.add(new TypeEntry(
                "index:///com/example/Hello.class",
                SOURCE_URI,
                "com/example/Hello",
                0x0001 /* ACC_PUBLIC */,
                new TypeRef.Resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(),
                List.of(new MethodEntry(
                        "index:///com/example/Hello.class",
                        "com/example/Hello",
                        0x0009 /* ACC_PUBLIC | ACC_STATIC */,
                        "greet",
                        TypeRef.Primitive.VOID,
                        List.of(),
                        List.of(),
                        List.of())),
                List.of(),
                List.of(),
                null));

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));

        JavacTool tool = JavacTool.create();
        Context context = new Context();

        IndexClassReader.preRegister(context, index, cp);

        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fileManager = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///Caller.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Hello;\n"
                        + "public class Caller {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        Hello.greet();\n"
                        + "    }\n"
                        + "}\n";
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter err = new StringWriter();

        JavacTask task = (JavacTask) tool.getTask(
                err,
                fileManager,
                diagnostics,
                List.of(),
                List.of(),
                List.of(src),
                context);

        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d);
            }
        }
        assertTrue(errors.isEmpty(),
                () -> "expected no errors, got:\n" + errors + "\nstderr:\n" + err);
    }

    @Test
    void indexedFieldIsVisibleToSourceReference() throws Exception {
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        index.add(new TypeEntry(
                "index:///com/example/Holder.class",
                SOURCE_URI,
                "com/example/Holder",
                0x0001,
                new TypeRef.Resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(new FieldEntry(
                        "index:///com/example/Holder.class",
                        "com/example/Holder",
                        0x0019 /* ACC_PUBLIC | ACC_STATIC | ACC_FINAL */,
                        "COUNT",
                        TypeRef.Primitive.INT,
                        List.of())),
                List.of(),
                List.of(),
                List.of(),
                null));

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///Consumer.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Holder;\n"
                        + "public class Consumer {\n"
                        + "    public int read() { return Holder.COUNT; }\n"
                        + "}\n";
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of(), List.of(), List.of(src), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
        }
        assertTrue(errors.isEmpty(), () -> "errors: " + errors);
    }

    @Test
    void classpathOrderPicksFirstDuplicate() throws Exception {
        String winnerUri = "index:///primary/";
        String loserUri = "index:///shadowed/";

        Index index = new Index();
        index.add(typeWithMethod(winnerUri, "java/lang/Object", "<init>"));
        index.add(typeWithMethod(winnerUri, "com/example/Dup", "primary"));
        index.add(typeWithMethod(loserUri, "com/example/Dup", "shadowed"));

        // Winner listed first in classpath order.
        ClasspathOrder cp = classPathOf(List.of(winnerUri, loserUri));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        // The consumer calls Dup.primary(); compilation must succeed, meaning
        // the file manager handed javac the winning entry.
        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///Use.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Dup;\n"
                        + "public class Use {\nvoid go() {\n Dup.primary();\n } }\n";
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of(), List.of(), List.of(src), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
        }
        assertTrue(errors.isEmpty(),
                () -> "winner should provide primary(); errors: " + errors);

        // Reverse the classpath order - now the shadowed entry wins and
        // primary() is no longer visible: compilation must fail.
        ClasspathOrder flipped = classPathOf(List.of(loserUri, winnerUri));
        JavacTool tool2 = JavacTool.create();
        Context ctx2 = new Context();
        IndexClassReader.preRegister(ctx2, index, flipped);
        StandardJavaFileManager std2 = tool2.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm2 = new IndexFileManager(std2, index, flipped);

        DiagnosticCollector<JavaFileObject> diag2 = new DiagnosticCollector<>();
        JavacTask task2 = (JavacTask) tool2.getTask(
                null, fm2, diag2, List.of(), List.of(), List.of(src), ctx2);
        task2.analyze();

        boolean sawCannotFindSymbol = false;
        for (Diagnostic<? extends JavaFileObject> d : diag2.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR
                    && d.getMessage(null).contains("primary")) {
                sawCannotFindSymbol = true;
            }
        }
        assertTrue(sawCannotFindSymbol,
                () -> "flipping the classpath should shadow primary(); got: "
                        + diag2.getDiagnostics());
    }


    @Test
    void genericIndexedTypeAcceptsTypeArguments() throws Exception {
        // Regression: javac used to report "type ... does not take parameters"
        // because IndexClassReader hard-wired typarams_field to an empty list.
        // With class-level type parameters synthesised, parameterised uses of
        // an indexed generic type must compile cleanly.
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/String", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Number", "<init>"));
        index.add(new TypeEntry(
                "index:///com/example/Box.class",
                SOURCE_URI,
                "com/example/Box",
                0x0001 /* ACC_PUBLIC */,
                new TypeRef.Resolved("java/lang/Object"),
                List.of(),
                List.of(TypeParamRef.of("T")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null));

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///UseBox.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Box;\n"
                        + "public class UseBox {\n"
                        + "    Box<String> stringBox;\n"
                        + "    Box<? extends Number> numberBox;\n"
                        + "}\n";
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of(), List.of(), List.of(src), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
        }
        assertTrue(errors.isEmpty(),
                () -> "Box<...> should be accepted because Box has one formal "
                        + "type parameter; got errors: " + errors);
    }

    @Test
    void classpathOrderIgnoresEntriesNotOnClasspath() {
        String onCp = "index:///on/";
        String offCp = "index:///off/";

        Index index = new Index();
        index.add(typeWithMethod(onCp, "com/example/On", "yes"));
        index.add(typeWithMethod(offCp, "com/example/Off", "nope"));

        ClasspathOrder cp = classPathOf(List.of(onCp));

        // Even though the index contains com/example/Off, an entry whose
        // source isn't on the classpath must not leak through.
        assertEquals(1, index.getAll("com/example/On").size());
        assertEquals(1, index.getAll("com/example/Off").size());
        TypeEntry onWinner = cp.pick(index.getAll("com/example/On"), TypeEntry::sourceUri);
        TypeEntry offWinner = cp.pick(index.getAll("com/example/Off"), TypeEntry::sourceUri);
        assertTrue(onWinner != null, "On should have a winner");
        assertTrue(offWinner == null, "Off should be filtered out by the classpath");
    }

    @Test
    void genericOverrideWithSuperWildcardCompilesCleanly() throws Exception {
        Path resources = Path.of("src/test/resources/ch/castleridge/javals/test");
        if (!Files.exists(resources)) {
            resources = Path.of("java-ls/src/test/resources/ch/castleridge/javals/test");
        }

        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Throwable", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Error", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Exception", "<init>"));
        index.add(typeWithMethod(SOURCE_URI, "java/lang/RuntimeException", "<init>"));
        indexSourceFile(index, resources.resolve("Expectation.java"));
        indexSourceFile(index, resources.resolve("Completable.java"));
        indexSourceFile(index, resources.resolve("Future.java"));

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));
        String futureBaseSource = Files.readString(resources.resolve("FutureBase.java"));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///FutureBase.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return futureBaseSource;
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of(), List.of(), List.of(src), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d);
            }
        }
        assertTrue(errors.isEmpty(),
                () -> "FutureBase.expecting should override Future.expecting cleanly; got: " + errors);
    }

    private static void indexSourceFile(Index index, Path file) throws Exception {
        String source = Files.readString(file);
        String fileName = file.getFileName().toString();
        SourceIndexer.index(
                URI.create("mem:///" + fileName),
                URI.create(SOURCE_URI),
                source,
                index);
    }

    @Test
    void sourceIndexedInterfacePrivateMethodStaysPrivate() throws Exception {
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        SourceIndexer.index(
                URI.create("mem:///Api.java"),
                URI.create(SOURCE_URI),
                "package com.example;\n"
                        + "public interface Api {\n"
                        + "    private static void hidden() {}\n"
                        + "    static void exposed() {}\n"
                        + "}\n",
                index);
        TypeEntry api = index.get("com/example/Api");
        assertNotNull(api, "SourceIndexer should emit com/example/Api");
        MethodEntry hidden = api.methods().stream()
                .filter(m -> m.name().equals("hidden"))
                .findFirst()
                .orElseThrow();
        assertTrue((hidden.accessFlags() & 0x0002) != 0,
                "hidden() should remain private in indexed flags");
        assertTrue((hidden.accessFlags() & 0x0001) == 0,
                "hidden() must not be marked public");

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject ok = new SimpleJavaFileObject(
                URI.create("test:///UseApiOk.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Api;\n"
                        + "public class UseApiOk {\n"
                        + "  void use() { Api.exposed(); }\n"
                        + "}\n";
            }
        };

        DiagnosticCollector<JavaFileObject> okDiagnostics = new DiagnosticCollector<>();
        JavacTask okTask = (JavacTask) tool.getTask(
                null, fm, okDiagnostics, List.of(), List.of(), List.of(ok), context);
        okTask.analyze();
        List<Diagnostic<? extends JavaFileObject>> okErrors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : okDiagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) okErrors.add(d);
        }
        assertTrue(okErrors.isEmpty(), () -> "Api.exposed() should compile, got: " + okErrors);

    }

    private static TypeEntry typeWithMethod(String srcUri, String jvmName, String methodName) {
        return new TypeEntry(
                "index:///" + jvmName + "@" + srcUri,
                srcUri,
                jvmName,
                0x0001,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new MethodEntry(
                        "index:///" + jvmName + "@" + srcUri + "#" + methodName,
                        jvmName,
                        0x0009,
                        methodName,
                        TypeRef.Primitive.VOID,
                        List.of(),
                        List.of(),
                        List.of())),
                List.of(),
                List.of(),
                null);
    }
}
