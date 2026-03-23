package ch.castleridge.javals.indexing.classfile;

import ch.castleridge.javals.indexing.declaration.DeclarationFields;
import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.FieldCondition;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClassIndexTest {

    @Test
    void indexesCompiledClassRoundTrip() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK required");
        }

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Path dir = Files.createTempDirectory("javals-class-index");
            Path source =
                    Files.writeString(
                            dir.resolve("Sample.java"),
                            """
                            package idx;
                            public class Sample {
                              private int x;
                              void run(java.io.PrintStream o) {
                                o.println(x);
                                new Object();
                              }
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

            Path classFile = dir.resolve(Path.of("idx", "Sample.class"));
            byte[] bytes = Files.readAllBytes(classFile);
            URI uri = classFile.toUri();

            JavaClassIndex index = new JavaClassIndex(exec);
            index.indexClassFile(uri, bytes).join();

            DeclarationIndex decl = index.declarations();
            List<IndexEntry> declRows = new ArrayList<>();
            decl.store()
                    .search(
                            SearchPredicate.allOf(
                                    new FieldCondition(DeclarationFields.RESOURCE_URI, uri.toString())),
                            declRows::add,
                            exec)
                    .join();

            assertTrue(declRows.size() >= 3, "type + at least one field + methods");
            long typeRows =
                    declRows.stream()
                            .filter(d -> DeclarationIndex.KIND_TYPE.equals(d.field(DeclarationFields.KIND)))
                            .count();
            assertEquals(1, typeRows);
            assertTrue(
                    declRows.stream()
                            .anyMatch(
                                    d ->
                                            DeclarationIndex.KIND_FIELD.equals(d.field(DeclarationFields.KIND))
                                                    && "x".equals(d.field(DeclarationFields.MEMBER_NAME))));
            assertTrue(
                    declRows.stream()
                            .anyMatch(
                                    d ->
                                            DeclarationIndex.KIND_METHOD.equals(d.field(DeclarationFields.KIND))
                                                    && "run".equals(d.field(DeclarationFields.MEMBER_NAME))));

            index.indexClassFile(uri, bytes).join();
            List<IndexEntry> declAfter = new ArrayList<>();
            decl.store()
                    .search(
                            SearchPredicate.allOf(
                                    new FieldCondition(DeclarationFields.RESOURCE_URI, uri.toString())),
                            declAfter::add,
                            exec)
                    .join();
            assertEquals(declRows.size(), declAfter.size(), "reindex should replace, not duplicate");
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
