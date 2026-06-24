package ch.castleridge.javals.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.bytecode.ClassFileIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.RealClassFileObject;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.scan.JrtInput;

class MinimalClassFileCompileTest {

    @Test
    void minimalScanServesRealClassFileObjectsAndCompiles(@TempDir Path dir) throws Exception {
        byte[] libBytes = compileClass("public class Lib { public static int answer() { return 42; } }", "Lib", dir);
        Path libClass = dir.resolve("Lib.class");
        Files.write(libClass, libBytes);

        String cpUri = dir.toUri().toString();

        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();
        String objectResource = jrtUri + "!/modules/java.base/java/lang/Object.class";

        Index index = new Index();
        ClassFileIndexer.index(libClass.toUri(), URI.create(cpUri), libBytes, index, true);
        index.addClassFile(new ClassFileEntry(objectResource, jrtUri, "java/lang/Object"));

        assertEquals(2, index.classFileSize());
        assertEquals(0, index.size());

        ClasspathOrder cp = new ClasspathOrder(
                List.of(UriClasspathEntry.of(cpUri), UriClasspathEntry.of(jrtUri)), false);

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);
        try {
            JavaFileObject lib = fm.getJavaFileForInput(StandardLocation.CLASS_PATH, "Lib", JavaFileObject.Kind.CLASS);
            assertNotNull(lib);
            assertInstanceOf(RealClassFileObject.class, lib);
            try (var in = lib.openInputStream()) {
                assertTrue(in.readAllBytes().length > 0);
            }

            JavaFileObject object = fm.getJavaFileForInput(
                    StandardLocation.CLASS_PATH, "java.lang.Object", JavaFileObject.Kind.CLASS);
            assertNotNull(object);
            assertInstanceOf(RealClassFileObject.class, object);

            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("test:///UseLib.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return """
                            public class UseLib {
                                int x = Lib.answer();
                            }
                            """;
                }
            };

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) tool.getTask(
                    null, fm, diagnostics, List.of(), List.of(), List.of(source), context);
            task.analyze();

            long errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
            assertEquals(0, errors, () -> "compile errors: " + diagnostics.getDiagnostics());
        } finally {
            fm.close();
        }
    }

    private static byte[] compileClass(String source, String className, Path dir) throws Exception {
        Path outDir = dir.resolve("out");
        Files.createDirectories(outDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(
                null, Locale.getDefault(), StandardCharsets.UTF_8);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
        JavaFileObject src = new SimpleJavaFileObject(
                URI.create("mem:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        assertTrue(compiler.getTask(null, fm, d -> {}, List.of(), List.of(), List.of(src)).call());
        fm.close();
        return Files.readAllBytes(outDir.resolve(className + ".class"));
    }
}
