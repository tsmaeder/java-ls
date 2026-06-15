package ch.castleridge.javals.javac;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.lang.model.element.Element;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.stream.Collectors;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLocatorNestedClassTest {

    @Test
    void base64EncoderImportNavigatesToInnerClassInJdkSources() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip),
                "JDK src.zip not present");

        JrtInput jrt = new JrtInput(jdk);
        String jrtUri = jrt.sourceUri();

        Index index = new Index();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        TypeEntry encoder = index.get("java/util/Base64$Encoder");
        assertNotNull(encoder, "Base64$Encoder must be indexed from jrt");

        String source = """
                import java.util.Base64.Encoder;

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
        long encoderOffset = lm.getPosition(1, "import java.util.Base64.Encoder;".indexOf("Encoder") + 1);

        TreePath path = TreePathLocator.findAt(trees, cu, encoderOffset);
        assertNotNull(path, "cursor must land on an AST node");

        Element element = trees.getElement(path);
        if (element == null && path.getParentPath() != null) {
            element = trees.getElement(path.getParentPath());
        }
        assertNotNull(element, "Encoder import must bind to a symbol");

        Map<String, String> sourceJarByBinaryJar = Map.of(jrtUri, srcZip.toUri().toString());
        SymbolLocator locator = new SymbolLocator(new SourceCache());
        Optional<Location> location = locator.locate(
                element, trees, cu, docUri.toString(), sourceJarByBinaryJar);

        assertTrue(location.isPresent(), "go-to-definition for Base64.Encoder must resolve");
        Location loc = location.get();
        assertTrue(loc.getUri().contains("Base64.java"),
                () -> "definition should open Base64.java, got: " + loc.getUri());
    }
}
