package ch.castleridge.javals.javac;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.SourceIndexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
                        Type.Primitive.VOID,
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
                        Type.Primitive.INT,
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
    void indexedNestedInterfaceIsVisibleAsQualifiedType() throws Exception {
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        index.add(new TypeEntry(
                "index:///com/example/Foo.class",
                SOURCE_URI,
                "com/example/Foo",
                Opcodes.ACC_PUBLIC,
                TypeDeclKind.CLASS,
                new TypeRef.Resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("com/example/Foo$Bar"),
                List.of(),
                null));
        index.add(new TypeEntry(
                "index:///com/example/Foo$Bar.class",
                SOURCE_URI,
                "com/example/Foo$Bar",
                Opcodes.ACC_PUBLIC,
                TypeDeclKind.INTERFACE,
                null,
                List.of(),
                List.of(),
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
                URI.create("test:///Use.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Foo;\n"
                        + "public class Use {\n"
                        + "    Foo.Bar x = null;\n"
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
        assertTrue(errors.isEmpty(), () -> "Foo.Bar should compile, got: " + errors);
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
    void indexClassReader2ResolvesClassAndMethodTypeVariables() throws Exception {
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
                List.of(new FieldEntry(
                        "index:///com/example/Box.class#value",
                        "com/example/Box",
                        0x0001,
                        "value",
                        Type.typeVariable("T"),
                        null,
                        List.of())),
                List.of(new MethodEntry(
                        "index:///com/example/Box.class#get",
                        "com/example/Box",
                        0x0009 /* ACC_PUBLIC | ACC_STATIC */,
                        "get",
                        Type.typeVariable("R"),
                        List.of(new ParameterEntry("x", 0, Type.typeVariable("R"), List.of())),
                        List.of(),
                        List.of(TypeParamRef.of("R")),
                        false,
                        false,
                        null,
                        List.of())),
                List.of(),
                List.of(),
                null));

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader2.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///UseGenerics2.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        import com.example.Box;
                        public class UseGenerics2 {
                            Box<String> stringBox;
                            String s = Box.<String>get(null);
                        }
                        """;
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
                () -> "IndexClassReader2 should resolve class and method type variables; got: " + errors);
    }

    @Test
    void indexedGenericBoundIsEnforcedAtUseSite() throws Exception {
        // Compile a bounded generic to bytecode, index it, and assert
        // that javac rejects a use that doesn't satisfy the bound when
        // the type is read back through IndexClassReader.
        Path outDir = Files.createTempDirectory("bounded-box");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject boxSrc = new SimpleJavaFileObject(
                    URI.create("mem:///BoundedBox.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class BoundedBox<T extends Comparable<T>> {
                                public T value;
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(boxSrc)).call(),
                    "BoundedBox should compile");
            byte[] boxBytes = Files.readAllBytes(outDir.resolve("BoundedBox.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String boxUri = "index:///cp/box/";
            ClassFileIndexer.index(
                    URI.create("index:///BoundedBox.class"),
                    URI.create(boxUri),
                    boxBytes,
                    index);

            ClasspathOrder cp = classPathOf(List.of(boxUri, jrtUri));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject bad = new SimpleJavaFileObject(
                    URI.create("test:///Bad.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class Bad {
                                BoundedBox<Object> b;
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(bad), context);
            task.analyze();

            boolean sawBoundError = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                String msg = d.getMessage(null);
                String code = d.getCode();
                if ((msg != null && (msg.contains("Comparable")
                        || msg.contains("type argument")
                        || msg.contains("bounds")))
                        || (code != null && code.contains("not.within.bounds"))) {
                    sawBoundError = true;
                    break;
                }
            }
            assertTrue(sawBoundError,
                    () -> "BoundedBox<Object> should be rejected because Object doesn't implement Comparable<Object>; got: "
                            + diagnostics.getDiagnostics());

            // Sanity: a compliant use must still compile cleanly.
            JavacTool tool2 = JavacTool.create();
            Context ctx2 = new Context();
            IndexClassReader.preRegister(ctx2, index, cp);
            StandardJavaFileManager std2 = tool2.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm2 = new IndexFileManager(std2, index, cp);

            JavaFileObject good = new SimpleJavaFileObject(
                    URI.create("test:///Good.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class Good {
                                BoundedBox<String> b;
                            }
                            """;
                }
            };
            DiagnosticCollector<JavaFileObject> okDiag = new DiagnosticCollector<>();
            JavacTask okTask = (JavacTask) tool2.getTask(
                    null, fm2, okDiag, List.of(), List.of(), List.of(good), ctx2);
            okTask.analyze();
            List<Diagnostic<? extends JavaFileObject>> okErrors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : okDiag.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) okErrors.add(d);
            }
            assertTrue(okErrors.isEmpty(),
                    () -> "BoundedBox<String> should compile cleanly; got: " + okErrors);
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
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
    void varargsCallOnIndexedBytecodeMethodCompilesCleanly() throws Exception {
        String varargHolder = """
                public class VarargHolder {
                    public static void all(int... parts) {}
                }
                """;
        Path outDir = Files.createTempDirectory("vararg-holder");
        try {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager compileFm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        compileFm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("mem:///VarargHolder.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return varargHolder;
            }
        };
        JavaCompiler.CompilationTask compileTask = compiler.getTask(
                null, compileFm, d -> {}, List.of(), List.of(), List.of(src));
        assertTrue(compileTask.call(), "VarargHolder should compile");
        byte[] bytes = Files.readAllBytes(outDir.resolve("VarargHolder.class"));

        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        ClassFileIndexer.index(
                URI.create("index:///VarargHolder.class"),
                URI.create(SOURCE_URI),
                bytes,
                index);

        TypeEntry varargType = index.get("VarargHolder");
        assertNotNull(varargType);
        MethodEntry allMethod = varargType.methods().stream()
                .filter(m -> m.name().equals("all"))
                .findFirst()
                .orElseThrow();
        assertTrue(allMethod.varargs(), "bytecode indexer should record varargs on MethodEntry");

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));
        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject caller = new SimpleJavaFileObject(
                URI.create("test:///Caller.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        public class Caller {
                            void go() { VarargHolder.all(1, 2, 3); }
                        }
                        """;
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of(), List.of(), List.of(caller), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add(d);
            }
        }
        assertTrue(errors.isEmpty(),
                () -> "varargs call should compile against indexed bytecode; got: " + errors);
        } finally {
            Files.walk(outDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void completableFutureAssignableToCompletionStageWithJrtIndex() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        ClasspathOrder cp = classPathOf(List.of(jrtUri));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///CfToCs.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        import java.util.concurrent.CompletableFuture;
                        import java.util.concurrent.CompletionStage;

                        public class CfToCs {
                            static CompletionStage<String> f() {
                                CompletableFuture<String> cf = new CompletableFuture<>();
                                return cf;
                            }
                        }
                        """;
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
                () -> "CompletableFuture should be assignable to CompletionStage; got: " + errors);
    }

    @Test
    void mapEntryTypeArgumentsCompileCleanlyWithJrtIndex() throws Exception {
        // Regression: indexed nested static members (java.util.Map.Entry)
        // must not be treated as non-static/raw-outers by Attr.
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        ClasspathOrder cp = classPathOf(List.of(jrtUri));
        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///MapEntryUse.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        import java.lang.Object;

                        class MapEntryUse {
                            static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json) {}
                        }
                        """;
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
                () -> "Map.Entry<String,Object> should compile cleanly with indexed JRT classes; got: " + errors);
    }

    @Test
    void annotationsOnIndexedJdkTypesAreRecognised() throws Exception {
        // Regression: when java.lang.SuppressWarnings was loaded via the
        // index reader its ClassSymbol kept the default notAnAnnotationType()
        // metadata, so every supplied element looked like a duplicate.
        // Check that the canonical "@SuppressWarnings + @Deprecated + @Override"
        // combination compiles cleanly against a JRT-backed index AND
        // that @SuppressWarnings("unchecked") actually suppresses the
        // unchecked-cast warning (Part 2: annotation values flow into
        // Lint via the symbol's declaration attributes).
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        ClasspathOrder cp = classPathOf(List.of(jrtUri));

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///Utils.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return """
                        public class Utils {
                            @SuppressWarnings("unchecked")
                            public static <E extends Throwable> void throwAsUnchecked(Throwable t) throws E {
                                throw (E) t;
                            }

                            @Deprecated
                            public void old() {}

                            @Override
                            public String toString() { return "x"; }
                        }
                        """;
            }
        };

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) tool.getTask(
                null, fm, diagnostics, List.of("-Xlint:unchecked"), List.of(), List.of(src), context);
        task.analyze();

        List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        List<Diagnostic<? extends JavaFileObject>> uncheckedWarnings = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
            String code = d.getCode();
            if (code != null && code.contains("unchecked")) {
                uncheckedWarnings.add(d);
            }
        }
        assertTrue(errors.isEmpty(),
                () -> "@SuppressWarnings/@Deprecated/@Override on indexed JDK annotations should compile cleanly; got: " + errors);
        assertTrue(uncheckedWarnings.isEmpty(),
                () -> "@SuppressWarnings(\"unchecked\") must suppress the unchecked cast warning, got: " + uncheckedWarnings);
    }

    @Test
    void sourceIndexedSimpleNamedAnnotationIsResolved() throws Exception {
        // Source-indexed @Pin on Marked stores Unresolved("Pin"); the
        // class reader must resolve it via SourceResolutionHints imports.
        // Pin itself is bytecode-indexed (like a dependency on the CP).
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        Path outDir = Files.createTempDirectory("pin-anno");
        try {
            JavaCompiler bootstrap = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = bootstrap.getStandardFileManager(
                    null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject pinSrc = new SimpleJavaFileObject(
                    URI.create("mem:///Pin.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            package com.example;
                            public @interface Pin {
                                String value() default "";
                            }
                            """;
                }
            };
            assertTrue(bootstrap.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(pinSrc)).call(),
                    "Pin should compile");
            byte[] pinBytes = Files.readAllBytes(outDir.resolve("com").resolve("example").resolve("Pin.class"));

            String pinUri = "index:///cp/pin/";
            ClassFileIndexer.index(
                    URI.create("index:///Pin.class"),
                    URI.create(pinUri),
                    pinBytes,
                    index);

            SourceIndexer.index(
                    URI.create("mem:///Marked.java"),
                    URI.create(SOURCE_URI),
                    """
                    package com.example;
                    import com.example.Pin;
                    @Pin("marked")
                    public class Marked {}
                    """,
                    index);

            TypeEntry markedEntry = index.get("com/example/Marked");
            assertNotNull(markedEntry);
            assertFalse(markedEntry.annotations().isEmpty());
            assertInstanceOf(TypeRef.Unresolved.class, markedEntry.annotations().get(0).annotationType());

            ClasspathOrder cp = classPathOf(List.of(pinUri, SOURCE_URI, jrtUri));
            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(
                    null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("test:///UseMarked.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return "import com.example.Marked;\n"
                            + "public class UseMarked {\n"
                            + "    Marked m;\n"
                            + "}\n";
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
                    () -> "UseMarked should compile against indexed Marked; got: " + errors);

            Elements elements = task.getElements();
            TypeElement marked = elements.getTypeElement("com.example.Marked");
            assertNotNull(marked);
            boolean hasPin = marked.getAnnotationMirrors().stream()
                    .anyMatch(am -> am.getAnnotationType().toString().equals("com.example.Pin"));
            assertTrue(hasPin,
                    "Indexed Marked must materialize @Pin resolved from a simple name");
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedTargetAnnotationIsEnforced() throws Exception {
        // Apply a user-written annotation type with @Target({METHOD}) to
        // a field in user code: javac should report a target-mismatch
        // error because the indexed @Target's value array reached
        // AnnotationTypeMetadata via IndexAnnotations.
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        // Compile a tiny annotation type with @Target({METHOD}) to a
        // temp dir, then index its bytecode into the same Index so the
        // file manager hands it back through IndexClassReader.
        Path outDir = Files.createTempDirectory("target-anno");
        try {
            JavaCompiler bootstrap = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = bootstrap.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject annSrc = new SimpleJavaFileObject(
                    URI.create("mem:///OnlyMethod.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            import java.lang.annotation.*;
                            @Target({ElementType.METHOD})
                            public @interface OnlyMethod {}
                            """;
                }
            };
            assertTrue(bootstrap.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(annSrc)).call(),
                    "annotation type should compile");
            byte[] annBytes = Files.readAllBytes(outDir.resolve("OnlyMethod.class"));

            String annUri = "index:///cp/onlymethod/";
            ClassFileIndexer.index(
                    URI.create("index:///OnlyMethod.class"),
                    URI.create(annUri),
                    annBytes,
                    index);

            ClasspathOrder cp = classPathOf(List.of(annUri, jrtUri));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("test:///Misuse.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class Misuse {
                                @OnlyMethod
                                int field = 0;
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(src), context);
            task.analyze();

            boolean sawTargetError = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                String code = d.getCode();
                String msg = d.getMessage(null);
                if (code != null && (code.contains("annotation.type.not.applicable")
                        || code.contains("annotation.not.applicable"))) {
                    sawTargetError = true;
                    break;
                }
                if (msg != null && msg.contains("not applicable")) {
                    sawTargetError = true;
                    break;
                }
            }
            assertTrue(sawTargetError,
                    () -> "@Target({METHOD}) on indexed annotation must reject application to a field; diagnostics: "
                            + diagnostics.getDiagnostics());
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedDeprecatedForRemovalIsReportedDistinctly() throws Exception {
        // An indexed method annotated @Deprecated(forRemoval = true) must
        // produce a "for removal" deprecation diagnostic at the call site,
        // not just the regular deprecation warning. This is the canonical
        // case for needing annotation values (not just presence) on
        // index-synthesized symbols.
        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri().toString();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        Path outDir = Files.createTempDirectory("deprecated-removal");
        try {
            JavaCompiler bootstrap = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = bootstrap.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject apiSrc = new SimpleJavaFileObject(
                    URI.create("mem:///GoneApi.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class GoneApi {
                                @Deprecated(forRemoval = true, since = "9")
                                public static void gone() {}
                            }
                            """;
                }
            };
            assertTrue(bootstrap.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(apiSrc)).call(),
                    "GoneApi should compile");
            byte[] apiBytes = Files.readAllBytes(outDir.resolve("GoneApi.class"));

            String apiUri = "index:///cp/gone/";
            ClassFileIndexer.index(
                    URI.create("index:///GoneApi.class"),
                    URI.create(apiUri),
                    apiBytes,
                    index);

            ClasspathOrder cp = classPathOf(List.of(apiUri, jrtUri));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("test:///Caller.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class Caller {
                                void go() { GoneApi.gone(); }
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of("-Xlint:removal"), List.of(), List.of(src), context);
            task.analyze();

            boolean sawForRemoval = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                String code = d.getCode();
                String msg = d.getMessage(null);
                if (code != null && code.contains("removal")) {
                    sawForRemoval = true;
                    break;
                }
                if (msg != null && msg.contains("marked for removal")) {
                    sawForRemoval = true;
                    break;
                }
            }
            assertTrue(sawForRemoval,
                    () -> "@Deprecated(forRemoval=true) on indexed method should produce a removal diagnostic; got: "
                            + diagnostics.getDiagnostics());
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
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
    void indexedStaticFinalConstantIsUsableAsCompileTimeConstant() throws Exception {
        // An indexed `public static final int N = 5;` field must reach the
        // compiler as a real compile-time constant: javac is only willing
        // to accept it as a case label and as a fixed array dimension when
        // VarSymbol.data carries the constant value.
        Path outDir = Files.createTempDirectory("indexed-constants");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///Constants.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class Constants {
                                public static final int N = 5;
                                public static final String GREETING = "hi";
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(src)).call(),
                    "Constants should compile");
            byte[] constantsBytes = Files.readAllBytes(outDir.resolve("Constants.class"));

            Index index = new Index();
            index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
            index.add(typeWithMethod(SOURCE_URI, "java/lang/String", "<init>"));

            String constantsUri = "index:///cp/constants/";
            ClassFileIndexer.index(
                    URI.create("index:///Constants.class"),
                    URI.create(constantsUri),
                    constantsBytes,
                    index);

            ClasspathOrder cp = classPathOf(List.of(constantsUri, SOURCE_URI));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject useCase = new SimpleJavaFileObject(
                    URI.create("test:///UseConst.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class UseConst {
                                int[] sized = new int[Constants.N];
                                String pick(int x) {
                                    switch (x) {
                                        case Constants.N: return Constants.GREETING;
                                        default: return "?";
                                    }
                                }
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(useCase), context);
            task.analyze();

            List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
            }
            assertTrue(errors.isEmpty(),
                    () -> "Constants.N must be a compile-time constant (usable as case label & array dim); got: " + errors);

            // Same setup with a non-final variant must reject the case
            // label, proving the constant-folding behaviour above came
            // from VarSymbol.data, not from leniency.
            Path outDir2 = Files.createTempDirectory("indexed-nonconst");
            try {
                JavaFileObject nonFinalSrc = new SimpleJavaFileObject(
                        URI.create("mem:///NonConst.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return """
                                public class NonConst {
                                    public static int N = 5;
                                }
                                """;
                    }
                };
                StandardJavaFileManager bfm2 = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
                bfm2.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir2.toFile()));
                assertTrue(compiler.getTask(null, bfm2, d -> {}, List.of(), List.of(), List.of(nonFinalSrc)).call(),
                        "NonConst should compile");
                byte[] nonConstBytes = Files.readAllBytes(outDir2.resolve("NonConst.class"));
                Index index2 = new Index();
                index2.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
                String nonConstUri = "index:///cp/nonconst/";
                ClassFileIndexer.index(
                        URI.create("index:///NonConst.class"),
                        URI.create(nonConstUri),
                        nonConstBytes,
                        index2);
                ClasspathOrder cp2 = classPathOf(List.of(nonConstUri, SOURCE_URI));
                JavacTool tool2 = JavacTool.create();
                Context ctx2 = new Context();
                IndexClassReader.preRegister(ctx2, index2, cp2);
                StandardJavaFileManager std2 = tool2.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
                IndexFileManager fm2 = new IndexFileManager(std2, index2, cp2);
                JavaFileObject use2 = new SimpleJavaFileObject(
                        URI.create("test:///UseNonConst.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return """
                                public class UseNonConst {
                                    String pick(int x) {
                                        switch (x) {
                                            case NonConst.N: return "ok";
                                            default: return "?";
                                        }
                                    }
                                }
                                """;
                    }
                };
                DiagnosticCollector<JavaFileObject> diag2 = new DiagnosticCollector<>();
                JavacTask task2 = (JavacTask) tool2.getTask(
                        null, fm2, diag2, List.of(), List.of(), List.of(use2), ctx2);
                task2.analyze();
                boolean sawConstantError = false;
                for (Diagnostic<? extends JavaFileObject> d : diag2.getDiagnostics()) {
                    if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                    String msg = d.getMessage(null);
                    if (msg != null && msg.contains("constant")) {
                        sawConstantError = true;
                        break;
                    }
                }
                assertTrue(sawConstantError,
                        () -> "case-label on a non-final indexed field must be rejected; got: "
                                + diag2.getDiagnostics());
            } finally {
                try (var paths = Files.walk(outDir2)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            }
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void synthesizedModuleInfoRoundTripsThroughClassFileIndexer() {
        // Use java.base's ModuleEntry to verify the synthesised bytes
        // are a faithful representation: re-index the synthesised
        // bytes and compare the resulting ModuleEntry to the original.
        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(
                List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "scan failures: " + failures);

        ModuleEntry original = index.getModule("java.base");
        assertNotNull(original, "java.base should be present");

        // Round-trip: synthesise -> re-index -> compare.
        IndexModuleFileObject mf = new IndexModuleFileObject(original);
        byte[] synthesized = mf.bytes();

        Index reindexed = new Index();
        ClassFileIndexer.index(
                URI.create("index:///roundtrip/java.base/module-info.class"),
                URI.create("index:///roundtrip/"),
                synthesized,
                reindexed);
        ModuleEntry afterRoundTrip = reindexed.getModule("java.base");
        assertNotNull(afterRoundTrip, "synthesised java.base should re-index");

        assertEquals(original.name(), afterRoundTrip.name());
        assertEquals(original.requires().size(), afterRoundTrip.requires().size(),
                "requires count must be preserved");
        assertEquals(original.exports().size(), afterRoundTrip.exports().size(),
                "exports count must be preserved");
        assertEquals(original.opens().size(), afterRoundTrip.opens().size(),
                "opens count must be preserved");
        assertEquals(original.uses().size(), afterRoundTrip.uses().size(),
                "uses count must be preserved");
        assertEquals(original.provides().size(), afterRoundTrip.provides().size(),
                "provides count must be preserved");
    }

    @Test
    void indexedUserModuleIsVisibleToModularCompilation() throws Exception {
        // Build a real module-info.class on disk so we can have a real
        // classpath URI; the indexer turns it into a ModuleEntry.
        Path outDir = Files.createTempDirectory("indexed-module");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            Path apiDir = outDir.resolve("com/example/api");
            Files.createDirectories(apiDir);
            JavaFileObject moduleInfoSrc = new SimpleJavaFileObject(
                    URI.create("mem:///module-info.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            module com.example {
                                requires java.base;
                                exports com.example.api;
                            }
                            """;
                }
            };
            JavaFileObject apiSrc = new SimpleJavaFileObject(
                    URI.create("mem:///com/example/api/Service.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            package com.example.api;
                            public class Service {
                                public static String hello() { return "hi"; }
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(),
                            List.of(moduleInfoSrc, apiSrc)).call(),
                    "com.example module should compile");
            byte[] moduleInfoBytes = Files.readAllBytes(outDir.resolve("module-info.class"));
            byte[] serviceBytes = Files.readAllBytes(outDir.resolve("com/example/api/Service.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String userUri = "index:///cp/com.example/";
            ClassFileIndexer.index(URI.create("index:///module-info.class"),
                    URI.create(userUri), moduleInfoBytes, index);
            ClassFileIndexer.index(URI.create("index:///com/example/api/Service.class"),
                    URI.create(userUri), serviceBytes, index);

            ModuleEntry comExample = index.getModule("com.example");
            assertNotNull(comExample, "com.example should be indexed");
            assertTrue(comExample.exports().stream().anyMatch(e -> e.packageJvm().equals("com/example/api")),
                    () -> "com.example should export com/example/api; got: " + comExample.exports());
            assertTrue(comExample.requires().stream().anyMatch(r -> r.moduleName().equals("java.base")),
                    () -> "com.example should require java.base; got: " + comExample.requires());

            // Verify the file manager surfaces the module via
            // listLocationsForModules so a modular compilation can see it.
            ClasspathOrder cp = classPathOf(List.of(userUri, jrtUri));
            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            // Confirm listLocationsForModules exposes the synthetic
            // location and inferModuleName resolves to "com.example".
            boolean found = false;
            for (Set<javax.tools.JavaFileManager.Location> set : fm.listLocationsForModules(StandardLocation.MODULE_PATH)) {
                for (javax.tools.JavaFileManager.Location loc : set) {
                    if ("com.example".equals(fm.inferModuleName(loc))) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found, "IndexFileManager should list com.example on MODULE_PATH");

            // And the synthesised module-info file object is retrievable
            // through the public API.
            IndexModuleFileObject mf = fm.moduleFile("com.example");
            assertNotNull(mf, "moduleFile should resolve com.example");
            assertEquals("com.example", mf.moduleName());

            // End-to-end: compile a separate module that requires
            // com.example from the index and calls Service.hello().
            JavaFileObject consumerModuleInfo = new SimpleJavaFileObject(
                    URI.create("test:///module-info.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            module consumer.app {
                                requires com.example;
                            }
                            """;
                }
            };
            JavaFileObject consumerUse = new SimpleJavaFileObject(
                    URI.create("test:///consumer/app/Use.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            package consumer.app;
                            import com.example.api.Service;
                            public class Use {
                                String go() { return Service.hello(); }
                            }
                            """;
                }
            };
            DiagnosticCollector<JavaFileObject> modularDiagnostics = new DiagnosticCollector<>();
            JavacTask modularTask = (JavacTask) tool.getTask(
                    null,
                    fm,
                    modularDiagnostics,
                    List.of("--module-path", outDir.toString()),
                    List.of(),
                    List.of(consumerModuleInfo, consumerUse),
                    context);
            modularTask.analyze();
            List<Diagnostic<? extends JavaFileObject>> modularErrors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : modularDiagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) modularErrors.add(d);
            }
            assertTrue(modularErrors.isEmpty(),
                    () -> "consumer module should resolve requires com.example, got: " + modularErrors);
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedTypeUseAnnotationFlowsThroughToTypeMirror() throws Exception {
        Path outDir = Files.createTempDirectory("indexed-typeuse");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///WithTypeUse.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            import java.lang.annotation.*;
                            @Target(ElementType.TYPE_USE)
                            @Retention(RetentionPolicy.RUNTIME)
                            @interface NN {}
                            public class WithTypeUse {
                                public @NN String hello() { return ""; }
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(src)).call(),
                    "WithTypeUse should compile");
            byte[] withBytes = Files.readAllBytes(outDir.resolve("WithTypeUse.class"));
            byte[] nnBytes = Files.readAllBytes(outDir.resolve("NN.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String typeUseUri = "index:///cp/typeuse/";
            ClassFileIndexer.index(URI.create("index:///WithTypeUse.class"), URI.create(typeUseUri), withBytes, index);
            ClassFileIndexer.index(URI.create("index:///NN.class"), URI.create(typeUseUri), nnBytes, index);

            // Sanity-check that the indexer wrapped the return TypeRef in
            // an Annotated decorator.
            TypeEntry withTypeUse = index.get("WithTypeUse");
            MethodEntry hello = withTypeUse.methods().stream()
                    .filter(m -> m.name().equals("hello"))
                    .findFirst().orElseThrow();
            assertTrue(hello.returnType() instanceof Type.Annotated,
                    () -> "Return type should be Annotated, was: " + hello.returnType());

            ClasspathOrder cp = classPathOf(List.of(typeUseUri, jrtUri));
            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject use = new SimpleJavaFileObject(
                    URI.create("test:///UseTypeUse.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class UseTypeUse {
                                String go() { return new WithTypeUse().hello(); }
                            }
                            """;
                }
            };
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, d -> {}, List.of(), List.of(), List.of(use), context);
            task.analyze();

            Elements elements = task.getElements();
            TypeElement withTypeUseTe = elements.getTypeElement("WithTypeUse");
            assertNotNull(withTypeUseTe);
            javax.lang.model.element.ExecutableElement helloEl = ElementFilter.methodsIn(withTypeUseTe.getEnclosedElements()).stream()
                    .filter(e -> e.getSimpleName().contentEquals("hello"))
                    .findFirst().orElseThrow();
            assertTrue(!helloEl.getReturnType().getAnnotationMirrors().isEmpty(),
                    () -> "@NN should appear on the return type mirror; mirrors: "
                            + helloEl.getReturnType().getAnnotationMirrors());
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedMethodExposesParameterNamesFlagsAndAnnotations() throws Exception {
        // Compile a class with a parameter annotation, MethodParameters,
        // and a `final` modifier on a parameter; then read it back through
        // the index and assert that the synthesised MethodSymbol.params
        // carry the same names, flags and annotations.
        Path outDir = Files.createTempDirectory("indexed-params");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("mem:///WithParams.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            import java.lang.annotation.*;
                            @Target(ElementType.PARAMETER)
                            @Retention(RetentionPolicy.RUNTIME)
                            @interface Tag {}
                            public class WithParams {
                                public void take(@Tag String s, final int n) {}
                            }
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {},
                    List.of("-parameters"),
                    List.of(), List.of(src)).call(),
                    "WithParams should compile");
            byte[] withParamsBytes = Files.readAllBytes(outDir.resolve("WithParams.class"));
            byte[] tagBytes = Files.readAllBytes(outDir.resolve("Tag.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String paramsUri = "index:///cp/params/";
            ClassFileIndexer.index(URI.create("index:///WithParams.class"), URI.create(paramsUri), withParamsBytes, index);
            ClassFileIndexer.index(URI.create("index:///Tag.class"), URI.create(paramsUri), tagBytes, index);

            // Sanity-check the indexer captured what we expect before we
            // even hand it to javac.
            TypeEntry withParamsType = index.get("WithParams");
            MethodEntry take = withParamsType.methods().stream()
                    .filter(m -> m.name().equals("take"))
                    .findFirst().orElseThrow();
            assertEquals(2, take.parameters().size());
            assertEquals("s", take.parameters().get(0).name());
            assertEquals("n", take.parameters().get(1).name());
            assertTrue((take.parameters().get(1).modifiers() & Opcodes.ACC_FINAL) != 0,
                    "Second parameter should carry ACC_FINAL");
            assertEquals(1, take.parameters().get(0).annotations().size(),
                    "First parameter should carry @Tag");

            ClasspathOrder cp = classPathOf(List.of(paramsUri, jrtUri));
            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject caller = new SimpleJavaFileObject(
                    URI.create("test:///UseParams.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class UseParams {
                                void go() { new WithParams().take("x", 1); }
                            }
                            """;
                }
            };
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, d -> {}, List.of(), List.of(), List.of(caller), context);
            task.analyze();

            Elements elements = task.getElements();
            TypeElement withParams = elements.getTypeElement("WithParams");
            assertNotNull(withParams);
            javax.lang.model.element.ExecutableElement takeEl = ElementFilter.methodsIn(withParams.getEnclosedElements()).stream()
                    .filter(e -> e.getSimpleName().contentEquals("take"))
                    .findFirst().orElseThrow();
            assertEquals(2, takeEl.getParameters().size());
            assertEquals("s", takeEl.getParameters().get(0).getSimpleName().toString());
            assertEquals("n", takeEl.getParameters().get(1).getSimpleName().toString());
            assertTrue(takeEl.getParameters().get(1).getModifiers().contains(Modifier.FINAL),
                    "Second parameter symbol should be final");
            assertEquals(1, takeEl.getParameters().get(0).getAnnotationMirrors().size(),
                    "First parameter symbol should expose its declaration annotation");
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedRecordExposesComponentsAndAccessors() throws Exception {
        Path outDir = Files.createTempDirectory("indexed-record");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject recSrc = new SimpleJavaFileObject(
                    URI.create("mem:///Point.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public record Point(int x, int y) {}
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(recSrc)).call(),
                    "Point should compile");
            byte[] pointBytes = Files.readAllBytes(outDir.resolve("Point.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String recUri = "index:///cp/point/";
            ClassFileIndexer.index(URI.create("index:///Point.class"), URI.create(recUri), pointBytes, index);

            TypeEntry pointEntry = index.get("Point");
            assertNotNull(pointEntry);
            assertEquals(2, pointEntry.recordComponents().size(),
                    () -> "ClassFileIndexer should capture record components from Record attribute");

            ClasspathOrder cp = classPathOf(List.of(recUri, jrtUri));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject use = new SimpleJavaFileObject(
                    URI.create("test:///UsePoint.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class UsePoint {
                                int sumX() { return new Point(1, 2).x(); }
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(use), context);
            task.analyze();

            List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
            }
            assertTrue(errors.isEmpty(),
                    () -> "Point record should be usable through accessors and the canonical constructor; got: " + errors);

            Elements elements = task.getElements();
            TypeElement point = elements.getTypeElement("Point");
            assertNotNull(point);
            assertEquals(2, point.getRecordComponents().size(),
                    "Indexed record should expose two RecordComponentElements");
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedSealedTypeRejectsForeignSubclass() throws Exception {
        Path outDir = Files.createTempDirectory("sealed-shape");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StandardJavaFileManager bfm = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            bfm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            JavaFileObject shapeSrc = new SimpleJavaFileObject(
                    URI.create("mem:///Shape.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public sealed interface Shape permits Circle, Square {}
                            final class Circle implements Shape {}
                            final class Square implements Shape {}
                            """;
                }
            };
            assertTrue(compiler.getTask(null, bfm, d -> {}, List.of(), List.of(), List.of(shapeSrc)).call(),
                    "Shape hierarchy should compile");
            byte[] shapeBytes = Files.readAllBytes(outDir.resolve("Shape.class"));
            byte[] circleBytes = Files.readAllBytes(outDir.resolve("Circle.class"));
            byte[] squareBytes = Files.readAllBytes(outDir.resolve("Square.class"));

            Path jdk = Path.of(System.getProperty("java.home"));
            JrtInput jrt = new JrtInput(jdk);
            String jrtUri = jrt.sourceUri().toString();

            Index index = new Index();
            List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
            assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

            String shapeUri = "index:///cp/shape/";
            ClassFileIndexer.index(URI.create("index:///Shape.class"), URI.create(shapeUri), shapeBytes, index);
            ClassFileIndexer.index(URI.create("index:///Circle.class"), URI.create(shapeUri), circleBytes, index);
            ClassFileIndexer.index(URI.create("index:///Square.class"), URI.create(shapeUri), squareBytes, index);

            ClasspathOrder cp = classPathOf(List.of(shapeUri, jrtUri));

            JavacTool tool = JavacTool.create();
            Context context = new Context();
            IndexClassReader.preRegister(context, index, cp);
            StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
            IndexFileManager fm = new IndexFileManager(std, index, cp);

            JavaFileObject bad = new SimpleJavaFileObject(
                    URI.create("test:///Triangle.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public final class Triangle implements Shape {}
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(bad), context);
            task.analyze();

            boolean sawSealedError = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                String msg = d.getMessage(null);
                String code = d.getCode();
                if ((msg != null && msg.contains("sealed"))
                        || (code != null && code.contains("sealed"))) {
                    sawSealedError = true;
                    break;
                }
            }
            assertTrue(sawSealedError,
                    () -> "Triangle should be rejected as a non-permitted subclass of sealed Shape; got: "
                            + diagnostics.getDiagnostics());
        } finally {
            try (var paths = Files.walk(outDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    @Test
    void indexedInterfaceDefaultMethodIsRecognisedAsDefault() throws Exception {
        Index index = new Index();
        index.add(typeWithMethod(SOURCE_URI, "java/lang/Object", "<init>"));
        SourceIndexer.index(
                URI.create("mem:///Greeter.java"),
                URI.create(SOURCE_URI),
                "package com.example;\n"
                        + "public interface Greeter {\n"
                        + "    default String greet() { return \"hi\"; }\n"
                        + "}\n",
                index);

        ClasspathOrder cp = classPathOf(List.of(SOURCE_URI));
        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);

        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("test:///UseGreeter.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "import com.example.Greeter;\n"
                        + "public class UseGreeter {\n"
                        + "    Greeter g;\n"
                        + "}\n";
            }
        };

        JavacTask task = (JavacTask) tool.getTask(
                null, fm, d -> {}, List.of(), List.of(), List.of(src), context);
        task.analyze();

        Elements elements = task.getElements();
        TypeElement greeter = elements.getTypeElement("com.example.Greeter");
        assertNotNull(greeter);
        javax.lang.model.element.ExecutableElement greet = ElementFilter.methodsIn(greeter.getEnclosedElements()).stream()
                .filter(e -> e.getSimpleName().contentEquals("greet"))
                .findFirst()
                .orElseThrow();
        assertTrue(greet.isDefault(),
                "Greeter.greet should be reported as a default method");
        assertTrue(greeter.getModifiers().contains(Modifier.ABSTRACT),
                "the interface itself is still abstract");
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
        long hiddenFlags = IndexAccessFlags.methodFlags(api, hidden);
        assertTrue((hiddenFlags & Opcodes.ACC_PRIVATE) != 0,
                "hidden() should remain private after flag synthesis");
        assertTrue((hiddenFlags & Opcodes.ACC_PUBLIC) == 0,
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

        Elements elements = okTask.getElements();
        TypeElement apiType = elements.getTypeElement("com.example.Api");
        assertNotNull(apiType);
        Element hiddenMethod = ElementFilter.methodsIn(elements.getAllMembers(apiType)).stream()
                .filter(e -> e.getSimpleName().contentEquals("hidden"))
                .findFirst()
                .orElseThrow();
        assertTrue(hiddenMethod.getModifiers().contains(Modifier.PRIVATE),
                "hidden() symbol should be private");
        assertTrue(!hiddenMethod.getModifiers().contains(Modifier.PUBLIC),
                "hidden() symbol must not be public");
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
                        Type.Primitive.VOID,
                        List.of(),
                        List.of(),
                        List.of())),
                List.of(),
                List.of(),
                null);
    }
}
