package ch.castleridge.javals.indexing.cli;

import ch.castleridge.javals.indexing.classfile.JavaClassIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import ch.castleridge.javals.indexing.store.SearchPredicate;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexDecompilerIntegrationTest {

    @Test
    void indexesCompiledClassAndRenderContainsDeclarations() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK required");
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Path dir = Files.createTempDirectory("javals-index-cli");
            Path source =
                    Files.writeString(
                            dir.resolve("CliSample.java"),
                            """
                            package idxcli;
                            public class CliSample {
                              private int x;
                              public void run() {}
                            }
                            """);

            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null)) {
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(source.toFile());
                boolean ok =
                        compiler.getTask(null, fm, diags, List.of("-d", dir.toString()), null, units)
                                .call();
                assertTrue(ok, diags.getDiagnostics().toString());
            }

            Path classFile = dir.resolve(Path.of("idxcli", "CliSample.class"));
            byte[] bytes = Files.readAllBytes(classFile);
            URI uri = classFile.toUri();

            JavaClassIndex index = new JavaClassIndex(exec);
            index.indexClassFile(uri, bytes).join();

            List<IndexEntry> all = new ArrayList<>();
            index.declarations()
                    .store()
                    .search(new SearchPredicate(Collections.emptyList()), all::add, exec)
                    .join();

            String rendered = IndexedSkeletonRenderer.renderAll(all);
            assertTrue(rendered.contains("package idxcli;"), rendered);
            assertTrue(rendered.contains("class CliSample"), rendered);
            assertTrue(rendered.contains("private int x;"), rendered);
            assertTrue(rendered.contains("public void run()"), rendered);
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
