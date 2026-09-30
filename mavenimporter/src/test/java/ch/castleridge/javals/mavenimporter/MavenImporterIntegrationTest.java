/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;

import ch.castleridge.javals.mavenimporter.mbt.MbtDocument;
import ch.castleridge.javals.mavenimporter.mbt.MbtNamespace;

/**
 * End-to-end imports against fixture Maven trees. Requires a working local Maven repo
 * (dependencies such as gson/junit are typically already cached).
 */
class MavenImporterIntegrationTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path temp;

    @Test
    void singleModuleEmitsMainAndTestWithExternalJar() throws Exception {
        Path workspace = copyFixture("fixtures/single-module");
        assertEquals(0, Main.run(new String[] {workspace.toString()}));

        MbtDocument doc = readMbt(workspace);
        String mainId = "ch.castleridge.fixtures:single-module:1.0.0";
        String testId = mainId + ":test";
        assertTrue(doc.namespaces.containsKey(mainId));
        assertTrue(doc.namespaces.containsKey(testId));

        MbtNamespace main = doc.namespaces.get(mainId);
        assertFalse(main.sources.isEmpty());
        assertTrue(main.dependencyModules.contains("com.google.code.gson:gson:2.11.0"));
        assertTrue(main.javacOptions.contains("-release") || main.javacOptions.contains("-source"));

        MbtNamespace test = doc.namespaces.get(testId);
        assertTrue(test.dependsOn.contains(mainId));
        assertTrue(test.dependencyModules.stream().anyMatch(id -> id.contains("junit")));

        assertTrue(doc.dependencyModules.stream()
                .anyMatch(m -> "com.google.code.gson:gson:2.11.0".equals(m.id) && m.jar != null));
    }

    @Test
    void multiModuleUsesDependsOnForReactorLink() throws Exception {
        Path workspace = copyFixture("fixtures/multi-module");
        assertEquals(0, Main.run(new String[] {workspace.toString()}));

        MbtDocument doc = readMbt(workspace);
        String a = "ch.castleridge.fixtures:mod-a:1.0.0";
        String b = "ch.castleridge.fixtures:mod-b:1.0.0";
        assertTrue(doc.namespaces.containsKey(a));
        assertTrue(doc.namespaces.containsKey(b));

        MbtNamespace modB = doc.namespaces.get(b);
        assertTrue(modB.dependsOn.contains(a));
        assertFalse(modB.dependencyModules.contains(a));
    }

    @Test
    void siblingReactorsAreAggregated() throws Exception {
        Path workspace = copyFixture("fixtures/siblings");
        assertEquals(0, Main.run(new String[] {workspace.toString()}));

        MbtDocument doc = readMbt(workspace);
        assertTrue(doc.namespaces.containsKey("ch.castleridge.fixtures:sibling-one:1.0.0"));
        assertTrue(doc.namespaces.containsKey("ch.castleridge.fixtures:sibling-two:1.0.0"));
    }

    @Test
    void nestedOrphanImportedAsSecondReactor() throws Exception {
        Path workspace = copyFixture("fixtures/nested-orphan");
        assertEquals(0, Main.run(new String[] {workspace.toString()}));

        MbtDocument doc = readMbt(workspace);
        assertTrue(doc.namespaces.containsKey("ch.castleridge.fixtures:nested-child:1.0.0"));
        assertTrue(doc.namespaces.containsKey("ch.castleridge.fixtures:orphan:1.0.0"));
    }

    @Test
    void customOutputPathIsRelativeToInputDirectory() throws Exception {
        Path workspace = copyFixture("fixtures/single-module");
        assertEquals(0, Main.run(new String[] {workspace.toString(), "--output", "build/mbt.json"}));

        Path written = workspace.resolve("build/mbt.json");
        assertTrue(Files.isRegularFile(written));
        assertFalse(Files.exists(workspace.resolve(".metals/mbt.json.maven")));

        MbtDocument doc = GSON.fromJson(Files.readString(written), MbtDocument.class);
        assertTrue(doc.namespaces.containsKey("ch.castleridge.fixtures:single-module:1.0.0"));
    }

    @Test
    void secondRunSkipsRewriteWhenPomsUnchanged() throws Exception {
        Path workspace = copyFixture("fixtures/single-module");
        assertEquals(0, Main.run(new String[] {workspace.toString()}));

        Path output = workspace.resolve(".metals/mbt.json.maven");
        FileTime before = Files.getLastModifiedTime(output);

        assertEquals(0, Main.run(new String[] {workspace.toString()}));
        assertEquals(before, Files.getLastModifiedTime(output));
    }

    private Path copyFixture(String resourceRoot) throws Exception {
        Path dest = temp.resolve(resourceRoot.replace('/', '_'));
        URL url = MavenImporterIntegrationTest.class.getClassLoader().getResource(resourceRoot);
        if (url == null) {
            throw new IOException("Missing resource " + resourceRoot);
        }
        Path source = Path.of(url.toURI());
        copyTree(source, dest);
        return dest;
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static MbtDocument readMbt(Path workspace) throws IOException {
        String json = Files.readString(workspace.resolve(".metals/mbt.json.maven"));
        return GSON.fromJson(json, MbtDocument.class);
    }
}
