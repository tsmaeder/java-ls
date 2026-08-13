package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ch.castleridge.javals.analysis.ecj.EcjDeclarationLocator;
import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.JavacWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.SourceCache;
import ch.castleridge.javals.analysis.javac.SymbolLocator;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;
import ch.castleridge.javals.indexing.source.javac.JavacSourceIndexer;

/**
 * Type hierarchy (prepare / supertypes / subtypes) for both compiler backends.
 */
class TypeHierarchyTest {

    @TempDir
    Path workspace;

    private InMemoryIndex index;
    private ClasspathOrder classpath;
    private Map<String, String> attachedSources;
    private String workspaceUri;
    private URI useUri;

    @BeforeEach
    void setUp() throws Exception {
        index = new InMemoryIndex();
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip), "JDK src.zip not present");

        JrtInput jrt = new JrtInput(jdk);
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());

        workspaceUri = workspace.toUri().toString();
        if (!workspaceUri.endsWith("/")) {
            workspaceUri = workspaceUri + "/";
        }
        classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(workspaceUri),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        attachedSources = Map.of(jrt.sourceUri(), srcZip.toUri().toString());

        writeAndIndex("demo/Animal.java", """
                package demo;
                public class Animal {}
                """);
        writeAndIndex("demo/Mammal.java", """
                package demo;
                public class Mammal extends Animal {}
                """);
        writeAndIndex("demo/Dog.java", """
                package demo;
                public class Dog extends Mammal {}
                """);
        writeAndIndex("demo/Cat.java", """
                package demo;
                public class Cat extends Mammal {}
                """);
        writeAndIndex("demo/Pet.java", """
                package demo;
                public interface Pet {}
                """);
        writeAndIndex("demo/HouseDog.java", """
                package demo;
                public class HouseDog extends Dog implements Pet {}
                """);
        writeAndIndex("demo/Use.java", """
                package demo;
                class Use {
                    Mammal m;
                    Dog d;
                    Pet p;
                    HouseDog h;
                }
                """);
        useUri = workspace.resolve("demo/Use.java").toUri();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javac", "ecj"})
    void prepareAndWalkHierarchy(String backend) {
        AnalysisSession session = analyze(backend, useUri, read("demo/Use.java"));
        assertTrue(session.isUsable());

        TypeHierarchyItem mammal = session.prepareTypeHierarchy(positionOf("demo/Use.java", "Mammal")).orElseThrow();
        assertEquals("Mammal", mammal.getName());
        assertEquals("demo.Mammal", mammal.getDetail());

        List<TypeHierarchyItem> supers = session.typeHierarchySupertypes(mammal);
        assertEquals(Set.of("Animal"), names(supers));

        List<TypeHierarchyItem> animalSupers = session.typeHierarchySupertypes(supers.get(0));
        assertEquals(Set.of("Object"), names(animalSupers));

        List<TypeHierarchyItem> subtypes = session.typeHierarchySubtypes(mammal);
        assertEquals(Set.of("Dog", "Cat"), names(subtypes));

        TypeHierarchyItem dog = subtypes.stream()
                .filter(i -> "Dog".equals(i.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(Set.of("HouseDog"), names(session.typeHierarchySubtypes(dog)));

        TypeHierarchyItem pet = session.prepareTypeHierarchy(positionOf("demo/Use.java", "Pet")).orElseThrow();
        assertEquals(Set.of("HouseDog"), names(session.typeHierarchySubtypes(pet)));

        TypeHierarchyItem houseDog = session.prepareTypeHierarchy(positionOf("demo/Use.java", "HouseDog")).orElseThrow();
        assertEquals(Set.of("Dog", "Pet"), names(session.typeHierarchySupertypes(houseDog)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javac", "ecj"})
    void prepareOnFieldReturnsEmpty(String backend) {
        AnalysisSession session = analyze(backend, useUri, read("demo/Use.java"));
        assertTrue(session.isUsable());
        // Line 2: "    Mammal m;" — character on field name `m`
        assertTrue(session.prepareTypeHierarchy(new Position(2, 11)).isEmpty());
    }

    private void writeAndIndex(String relativePath, String source) throws Exception {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavacSourceIndexer.index(relativePath, workspaceUri, source, index);
    }

    private String read(String relativePath) {
        try {
            return Files.readString(workspace.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Position positionOf(String relativePath, String needle) {
        String source = read(relativePath);
        int offset = source.indexOf(needle);
        assertTrue(offset >= 0, "needle not found: " + needle);
        int line = 0;
        int lastBreak = -1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lastBreak = i;
            }
        }
        return new Position(line, offset - lastBreak - 1);
    }

    private AnalysisSession analyze(String backend, URI uri, String source) {
        WorkspaceCompiler compiler = "ecj".equals(backend)
                ? new EcjWorkspaceCompiler(new EcjDeclarationLocator(), attachedSources)
                : new JavacWorkspaceCompiler(new SymbolLocator(new SourceCache()), attachedSources);
        return compiler.analyze(uri, source, index, classpath);
    }

    private static Set<String> names(List<TypeHierarchyItem> items) {
        return items.stream().map(TypeHierarchyItem::getName).collect(Collectors.toSet());
    }
}
