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
import java.util.Map;
import java.util.Optional;

import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.PrunedSourceFileObject;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

class PrunedSourceCompileTest {

    @Test
    void prunedWorkspaceSourcesCompileOnSourcePath(@TempDir Path dir) throws Exception {
        Path srcRoot = dir.resolve("src");
        Path demoDir = srcRoot.resolve("demo");
        Files.createDirectories(demoDir);
        Path libJava = demoDir.resolve("Lib.java");
        Files.writeString(libJava, """
                package demo;
                public class Lib {
                    private int hidden = 1;
                    public static final String TAG = "lib";
                    public int answer() { return hidden + 41; }
                }
                """);

        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Index index = new Index();
        assertTrue(new Scanner(false, true).scanAll(List.of(jrt, new DirInput(srcRoot)), index).isEmpty());
        assertTrue(index.getAll("demo/Lib").isEmpty(), "workspace sources must not produce TypeEntry records");
        assertEquals(1, index.prunedSourceSize());

        ClasspathOrder cp = new ClasspathOrder(
                List.of(UriClasspathEntry.of(srcRoot.toUri().toString()), UriClasspathEntry.of(jrtUri)),
                false);

        JavacTool tool = JavacTool.create();
        Context context = new Context();
        IndexClassReader.preRegister(context, index, cp);
        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fm = new IndexFileManager(std, index, cp);
        try {
            assertTrue(fm.hasLocation(StandardLocation.SOURCE_PATH));

            JavaFileObject pruned = fm.getJavaFileForInput(
                    StandardLocation.SOURCE_PATH, "demo.Lib", JavaFileObject.Kind.SOURCE);
            assertNotNull(pruned);
            assertInstanceOf(PrunedSourceFileObject.class, pruned);
            assertTrue(pruned.getCharContent(true).toString().contains("TAG"));
            assertTrue(!pruned.getCharContent(true).toString().contains("hidden"));

            String consumer = """
                    package use;
                    import demo.Lib;
                    class Use {
                        int x = Lib.TAG.length() + new Lib().answer();
                    }
                    """;
            WorkspaceCompiler.Result result = WorkspaceCompiler.compile(
                    URI.create("mem:///Use.java"), consumer, index, cp);
            assertNotNull(result.cu());
            List<String> errors = result.diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .toList();
            assertTrue(errors.isEmpty(), () -> "compile errors: " + errors);
        } finally {
            fm.close();
        }
    }

    @Test
    void goToDefinitionUsesOriginalSourceNotStub(@TempDir Path dir) throws Exception {
        Path srcRoot = dir.resolve("src");
        Path demoDir = srcRoot.resolve("demo");
        Files.createDirectories(demoDir);
        Path libJava = demoDir.resolve("Lib.java");
        Files.writeString(libJava, """
                package demo;
                public class Lib {
                    private int hidden = 1;
                    public int answer() { return hidden + 41; }
                }
                """);

        Path jdk = Path.of(System.getProperty("java.home"));
        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Index index = new Index();
        assertTrue(new Scanner(false, true).scanAll(List.of(jrt, new DirInput(srcRoot)), index).isEmpty());

        ClasspathOrder cp = new ClasspathOrder(
                List.of(UriClasspathEntry.of(srcRoot.toUri().toString()), UriClasspathEntry.of(jrtUri)),
                false);

        String consumer = """
                package use;
                import demo.Lib;
                class Use {
                    int x = new Lib().answer();
                }
                """;
        URI docUri = URI.create("mem:///Use.java");
        WorkspaceCompiler.Result result = WorkspaceCompiler.compile(docUri, consumer, index, cp);
        assertNotNull(result.cu());
        Trees trees = result.trees();
        CompilationUnitTree cu = result.cu();

        var elements = result.task().getElements();
        var libType = elements.getTypeElement("demo.Lib");
        assertNotNull(libType, "demo.Lib must resolve");
        Element answer = libType.getEnclosedElements().stream()
                .filter(e -> e.getSimpleName().contentEquals("answer"))
                .findFirst()
                .orElse(null);
        assertNotNull(answer, "Lib.answer must resolve");

        SymbolLocator locator = new SymbolLocator(new SourceCache());
        Optional<Location> loc = locator.locate(answer, trees, cu, docUri.toString(), Map.of());
        assertTrue(loc.isPresent());
        assertEquals(libJava.toUri().toString(), loc.get().getUri());
        assertTrue(loc.get().getRange().getStart().getLine() > 0);
    }
}
