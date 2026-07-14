package ch.castleridge.javals.indexing.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.index.InMemorySource;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.PrunedSourceEntry;
import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.Scanner;

class SourcePrunerTest {

    @Test
    void keepsPrivateConstructorInPrunedStub() throws Exception {
        String pruned = prune("""
                package demo;
                public class Factory {
                    private Factory() {}
                    public static Factory create() { return null; }
                }
                """);

        assertTrue(pruned.contains("Factory()"),
                "private constructor must remain so stub has no implicit public default ctor");
        assertTrue(pruned.contains("create"));
        assertTrue(pruned.contains("private"));
    }

    @Test
    void dropsPrivateMembersAndStubsMethodBodies() throws Exception {
        String pruned = prune("""
                package demo;
                public class Foo {
                    public static final int CONST = 42;
                    private int hidden;
                    private void secret() {}
                    public int visible() { return hidden; }
                }
                """);

        assertTrue(pruned.contains("CONST"));
        assertTrue(pruned.contains("42"));
        assertTrue(pruned.contains("visible"));
        assertFalse(pruned.contains("hidden"));
        assertFalse(pruned.contains("secret"));
        assertTrue(pruned.contains("return 0") || pruned.contains("return 0;"));
    }

    @Test
    void keepsPackagePrivateNestedTypeAndStubsInterfaceDefault() throws Exception {
        String pruned = prune("""
                package demo;
                public class Outer {
                    class Inner {
                        public String name() { return "x"; }
                    }
                    private class Hidden {}
                }
                interface I {
                    default int size() { return 1; }
                    void run();
                }
                """);

        assertTrue(pruned.contains("Inner"));
        assertFalse(pruned.contains("Hidden"));
        assertTrue(pruned.contains("size"));
        assertTrue(pruned.contains("run"));
    }

    @Test
    void staticFinalBinaryInitializerIsStripped() throws Exception {
        String pruned = prune("""
                package demo;
                public class Foo {
                    public static final int SUM = 1 + 2;
                    public static final int NEG = -42;
                }
                """);

        assertTrue(pruned.contains("SUM"));
        assertFalse(pruned.contains("1 + 2"));
        assertTrue(pruned.contains("NEG"));
        assertTrue(pruned.contains("-42") || pruned.contains("- 42"));
    }

    @Test
    void prunedIndexerStoresEntryPerFile() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pruned-index");
        java.nio.file.Path javaFile = root.resolve("demo").resolve("Svc.java");
        java.nio.file.Files.createDirectories(javaFile.getParent());
        java.nio.file.Files.writeString(javaFile, """
                package demo;
                public class Svc {
                    public String go() { return "ok"; }
                    private void hidden() {}
                }
                """);

        Index index = new Index();
        List<Throwable> failures = new Scanner(false, true).scanAll(List.of(new DirInput(root)), index);
        assertTrue(failures.isEmpty());
        assertEquals(0, index.size());
        assertEquals(1, index.prunedSourceSize());

        PrunedSourceEntry entry = index.getPrunedSource(javaFile.toUri().toString());
        assertNotNull(entry);
        assertEquals("demo/Svc", entry.primaryBinaryName());
        assertFalse(entry.prunedText().toString().contains("hidden"));
    }

    private static String prune(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject input = new InMemorySource(URI.create("mem:///Test.java"), source);
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, d -> {}, List.of(), List.of(), List.of(input));
        CompilationUnitTree cu = task.parse().iterator().next();
        Context context = ((JavacTaskImpl) task).getContext();
        return SourcePruner.prune(cu, context);
    }
}
