package ch.castleridge.javals.analysis.ecj;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.ResolvedSymbol;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.index.InMemoryIndex;
import ch.castleridge.javals.indexing.scan.JarInput;
import ch.castleridge.javals.indexing.scan.JrtInput;
import ch.castleridge.javals.indexing.scan.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Navigation from an analysed unit into the source attached to the binaries
 * it compiles against: a dependency's {@code -sources.jar} and the JDK's
 * {@code src.zip}. The class files those declarations were resolved from must
 * never be the navigation target.
 */
class EcjAttachedSourceDefinitionTest {

    private static final String GREETER_SOURCE = """
            package com.example;

            public class Greeter {

                public static final String DEFAULT_NAME = "world";

                public String greet(String name) {
                    return "hello " + name;
                }

                public String greet(int times) {
                    return "hello".repeat(times);
                }

                public static class Loud {
                    public String shout(String name) {
                        return name.toUpperCase();
                    }
                }
            }
            """;

    @Test
    void navigatesIntoDependencySourcesJar(@TempDir Path workspace) throws Exception {
        Dependency dependency = buildDependency(workspace);
        Index index = new InMemoryIndex();
        JarInput jar = new JarInput(dependency.binaryJar());
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        Map<String, String> attached = Map.of(jar.sourceUri(), dependency.sourcesJar().toUri().toString());

        String source = """
                package demo;

                import com.example.Greeter;
                import com.example.Greeter.Loud;

                class Use {
                    String m(Greeter greeter, Loud loud) {
                        return greeter.greet("x") + loud.shout(Greeter.DEFAULT_NAME);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(new EcjDeclarationLocator(), attached).analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
        assertTrue(session.isUsable());

        String expectedUri = "jar:" + dependency.sourcesJar().toUri() + "!/com/example/Greeter.java";
        // 'Greeter' in "Greeter greeter"
        assertDefinition(session, new Position(6, 13), expectedUri, "Greeter", 2, 13);
        // 'Loud' in "Loud loud"
        assertDefinition(session, new Position(6, 30), expectedUri, "Loud", 14, 24);
        // 'greet' in "greeter.greet(\"x\")"
        assertDefinition(session, new Position(7, 24), expectedUri, "greet", 6, 18);
        // 'DEFAULT_NAME' in "Greeter.DEFAULT_NAME"
        assertDefinition(session, new Position(7, 55), expectedUri, "DEFAULT_NAME", 4, 31);
    }

    /**
     * The overload of {@code greet} taking an {@code int} must not resolve to
     * the {@code String} one: they only differ in parameter types.
     */
    @Test
    void picksTheCalledOverload(@TempDir Path workspace) throws Exception {
        Dependency dependency = buildDependency(workspace);
        Index index = new InMemoryIndex();
        JarInput jar = new JarInput(dependency.binaryJar());
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);
        Map<String, String> attached = Map.of(jar.sourceUri(), dependency.sourcesJar().toUri().toString());

        String source = """
                package demo;

                import com.example.Greeter;

                class Use {
                    String m(Greeter greeter) {
                        return greeter.greet(3);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(new EcjDeclarationLocator(), attached).analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
        assertTrue(session.isUsable());

        String expectedUri = "jar:" + dependency.sourcesJar().toUri() + "!/com/example/Greeter.java";
        assertDefinition(session, new Position(6, 24), expectedUri, "greet", 10, 18);
    }

    @Test
    void classFileWithoutAttachedSourcesIsNotANavigationTarget(@TempDir Path workspace) throws Exception {
        Dependency dependency = buildDependency(workspace);
        Index index = new InMemoryIndex();
        JarInput jar = new JarInput(dependency.binaryJar());
        JrtInput jrt = new JrtInput(Path.of(System.getProperty("java.home")));
        assertTrue(new Scanner().scanAll(List.of(jar, jrt), index).isEmpty());

        ClasspathOrder classpath = new ClasspathOrder(List.of(
                UriClasspathEntry.of(jar.sourceUri()),
                UriClasspathEntry.of(jrt.sourceUri())), false);

        String source = """
                package demo;

                import com.example.Greeter;

                class Use {
                    Greeter greeter;
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(new EcjDeclarationLocator(), Map.of()).analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
        assertTrue(session.isUsable());

        ResolvedSymbol resolved = session.resolveAt(new Position(5, 4)).orElseThrow();
        assertEquals("Greeter", resolved.identity().simpleName());
        assertTrue(session.definitionOf(resolved).isEmpty(),
                () -> "a class file is not navigable, got " + session.definitionOf(resolved));
    }

    @Test
    void navigatesIntoJdkSourceZip() throws Exception {
        Path jdk = Path.of(System.getProperty("java.home"));
        Path srcZip = jdk.resolve("lib/src.zip");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(srcZip), "JDK src.zip not present");

        Index index = new InMemoryIndex();
        JrtInput jrt = new JrtInput(jdk);
        assertTrue(new Scanner().scanAll(List.of(jrt), index).isEmpty());

        ClasspathOrder classpath =
                new ClasspathOrder(List.of(UriClasspathEntry.of(jrt.sourceUri())), false);
        Map<String, String> attached = Map.of(jrt.sourceUri(), srcZip.toUri().toString());

        String source = """
                package demo;

                import java.util.Base64;
                import java.util.Base64.Encoder;

                class Use {
                    String m(byte[] bytes) {
                        Encoder encoder = Base64.getEncoder();
                        return encoder.encodeToString(bytes);
                    }
                }
                """;
        AnalysisSession session = new EcjWorkspaceCompiler(new EcjDeclarationLocator(), attached).analyze(
                URI.create("file:///workspace/demo/Use.java"), source, index, classpath);
        assertTrue(session.isUsable());

        String base64Uri = "jar:" + srcZip.toUri() + "!/java.base/java/util/Base64.java";

        // 'Encoder' in "Encoder encoder"
        Location encoder = definitionAt(session, new Position(7, 8));
        assertEquals(base64Uri, encoder.getUri());
        assertNameAt(encoder, "Encoder", srcZip, "java.base/java/util/Base64.java");

        // 'getEncoder' in "Base64.getEncoder()"
        Location getEncoder = definitionAt(session, new Position(7, 33));
        assertEquals(base64Uri, getEncoder.getUri());
        assertNameAt(getEncoder, "getEncoder", srcZip, "java.base/java/util/Base64.java");

        // 'encodeToString' in "encoder.encodeToString(bytes)"
        Location encodeToString = definitionAt(session, new Position(8, 24));
        assertEquals(base64Uri, encodeToString.getUri());
        assertNameAt(encodeToString, "encodeToString", srcZip, "java.base/java/util/Base64.java");
    }

    private static void assertDefinition(AnalysisSession session,
                                         Position cursor,
                                         String expectedUri,
                                         String expectedName,
                                         int expectedLine,
                                         int expectedCharacter) {
        Location location = definitionAt(session, cursor);
        assertEquals(expectedUri, location.getUri());
        assertEquals(new Range(
                        new Position(expectedLine, expectedCharacter),
                        new Position(expectedLine, expectedCharacter + expectedName.length())),
                location.getRange(),
                () -> "unexpected range for " + expectedName + ": " + location.getRange());
    }

    private static Location definitionAt(AnalysisSession session, Position cursor) {
        ResolvedSymbol resolved = session.resolveAt(cursor).orElseThrow(
                () -> new AssertionError("nothing resolved at " + cursor));
        return session.definitionOf(resolved).orElseThrow(
                () -> new AssertionError("no definition for " + resolved.identity().simpleName()));
    }

    /** The reported range must actually spell the declared name. */
    private static void assertNameAt(Location location, String name, Path archive, String entry) throws Exception {
        String text;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            text = new String(zip.getInputStream(new ZipEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
        }
        String line = text.lines().skip(location.getRange().getStart().getLine()).findFirst().orElseThrow();
        int start = location.getRange().getStart().getCharacter();
        int end = location.getRange().getEnd().getCharacter();
        assertEquals(name, line.substring(start, end), () -> "range should cover the declared name in " + line);
    }

    private record Dependency(Path binaryJar, Path sourcesJar) {}

    private static Dependency buildDependency(Path workspace) throws Exception {
        Path sourceDir = Files.createDirectories(workspace.resolve("dep/src/com/example"));
        Path sourceFile = sourceDir.resolve("Greeter.java");
        Files.writeString(sourceFile, GREETER_SOURCE, StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(workspace.resolve("dep/classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8);
        files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
        boolean compiled = compiler.getTask(null, files, d -> {}, List.of(), List.of(),
                files.getJavaFileObjects(sourceFile)).call();
        assertTrue(compiled, "the dependency must compile");
        files.close();

        Path lib = Files.createDirectories(workspace.resolve("lib"));
        Path binaryJar = lib.resolve("dep.jar");
        Path sourcesJar = lib.resolve("dep-sources.jar");
        zip(binaryJar, Map.of(
                "com/example/Greeter.class", Files.readAllBytes(classes.resolve("com/example/Greeter.class")),
                "com/example/Greeter$Loud.class",
                Files.readAllBytes(classes.resolve("com/example/Greeter$Loud.class"))));
        zip(sourcesJar, Map.of(
                "com/example/Greeter.java", GREETER_SOURCE.getBytes(StandardCharsets.UTF_8)));
        return new Dependency(binaryJar, sourcesJar);
    }

    private static void zip(Path archive, Map<String, byte[]> entries) throws Exception {
        try (OutputStream out = Files.newOutputStream(archive);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}
