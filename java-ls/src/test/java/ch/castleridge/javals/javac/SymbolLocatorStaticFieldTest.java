package ch.castleridge.javals.javac;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLocatorStaticFieldTest {

    @Test
    void isoInstantStaticImportNavigatesToFieldInJdkSources() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip),
                "JDK src.zip not present");

        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        TypeEntry formatter = index.get("java/time/format/DateTimeFormatter");
        assertNotNull(formatter, "DateTimeFormatter must be indexed from jrt");
        FieldEntry isoInstant = Arrays.stream(formatter.fields())
                .filter(f -> "ISO_INSTANT".equals(f.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(isoInstant, "ISO_INSTANT field must be indexed on DateTimeFormatter");

        String source = """
                import static java.time.format.DateTimeFormatter.ISO_INSTANT;

                class Use {
                }
                """;
        URI docUri = URI.create("mem:///Use.java");
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrtUri).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        WorkspaceCompiler.Result compiled = WorkspaceCompiler.compile(docUri, source, index, cp);

        CompilationUnitTree cu = compiled.cu();
        assertNotNull(cu);
        Trees trees = compiled.trees();
        LineMap lm = cu.getLineMap();

        String importLine = "import static java.time.format.DateTimeFormatter.ISO_INSTANT;";
        long isoOffset = lm.getPosition(1, importLine.indexOf("ISO_INSTANT") + 1);

        TreePath isoPath = TreePathLocator.findAt(trees, cu, isoOffset);
        assertNotNull(isoPath, "cursor on ISO_INSTANT must land on an AST node");

        Map<String, String> sourceJarByBinaryJar = Map.of(jrtUri, srcZip.toUri().toString());
        SymbolLocator locator = new SymbolLocator(new SourceCache());
        Optional<Location> isoLoc = locator.locate(
                DefinitionElementResolver.resolve(trees, isoPath),
                trees, cu, docUri.toString(), sourceJarByBinaryJar);

        assertTrue(isoLoc.isPresent(), "go-to-definition for ISO_INSTANT must resolve");
        assertTrue(isoLoc.get().getUri().contains("DateTimeFormatter.java"),
                () -> "definition should open DateTimeFormatter.java, got: " + isoLoc.get().getUri());
    }
}
