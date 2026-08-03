package ch.castleridge.javals.javac;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionElementResolverTest {

    @Test
    void resolvesStaticImportMemberName() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(jdk),
                "JDK not present");

        JrtInput jrt = new JrtInput(jdk);
        Index index = new InMemoryIndex();
        List<Throwable> failures = new Scanner().scanAll(List.of(jrt), index);
        assertTrue(failures.isEmpty(), () -> "JRT scan failures: " + failures);

        String source = """
                import static java.time.format.DateTimeFormatter.ISO_INSTANT;

                class Use {
                }
                """;
        ClasspathOrder cp = new ClasspathOrder(
                List.of(jrt.sourceUri()).stream().map(UriClasspathEntry::of).collect(Collectors.toList()),
                false);
        WorkspaceCompiler.Result compiled = WorkspaceCompiler.compile(
                URI.create("mem:///Use.java"), source, index, cp);

        CompilationUnitTree cu = compiled.cu();
        assertNotNull(cu);
        Trees trees = compiled.trees();
        LineMap lm = cu.getLineMap();
        String importLine = "import static java.time.format.DateTimeFormatter.ISO_INSTANT;";
        long isoOffset = lm.getPosition(1, importLine.indexOf("ISO_INSTANT") + 1);

        TreePath path = TreePathLocator.findAt(trees, cu, isoOffset);
        assertNotNull(path);

        Element element = DefinitionElementResolver.resolve(trees, path);
        assertNotNull(element, "ISO_INSTANT in static import must resolve to a symbol");
        assertInstanceOf(VariableElement.class, element);
        assertTrue(element.getKind() == ElementKind.FIELD);
        assertTrue(element.getSimpleName().contentEquals("ISO_INSTANT"));
    }
}
