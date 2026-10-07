/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mavenimporter.mbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MbtJsonWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesMetalsSchemaUnderDotJavals() throws Exception {
        MbtDocument document = new MbtDocument();
        document.dependencyModules.add(new MbtDependencyModule(
                "com.google.code.gson:gson:2.11.0",
                "file:/repo/gson-2.11.0.jar",
                "file:/repo/gson-2.11.0-sources.jar"));
        MbtNamespace ns = new MbtNamespace();
        ns.sources.add("src/main/java");
        ns.javacOptions.add("-release");
        ns.javacOptions.add("25");
        ns.dependencyModules.add("com.google.code.gson:gson:2.11.0");
        ns.javaHome = "C:/jdk";
        ns.dependsOn.add("other:ns:1.0");
        document.namespaces.put("g:a:1.0", ns);

        Path output = temp.resolve(".javals/mbt.json");
        Path written = MbtJsonWriter.write(output, document);
        assertEquals(output.toAbsolutePath().normalize(), written);
        String json = Files.readString(written);
        assertTrue(json.contains("\"javacOptions\""));
        assertTrue(json.contains("\"src/main/java\""));
        assertTrue(json.contains("com.google.code.gson:gson:2.11.0"));
        assertFalse(json.contains("\"classDirectories\""));
        assertFalse(json.contains("\"projectPath\""));
    }

    @Test
    void writesToExplicitPathCreatingParents() throws Exception {
        MbtDocument document = new MbtDocument();
        document.namespaces.put("g:a:1.0", new MbtNamespace());

        Path output = temp.resolve("nested/out/mbt.json");
        Path written = MbtJsonWriter.write(output, document);
        assertEquals(output.toAbsolutePath().normalize(), written);
        assertTrue(Files.isRegularFile(written));
        assertTrue(Files.readString(written).contains("g:a:1.0"));
    }

    @Test
    void aggregatorDedupesDependencyModulesById() {
        MbtAggregator aggregator = new MbtAggregator();

        MbtDocument first = new MbtDocument();
        first.dependencyModules.add(new MbtDependencyModule("a:b:1", "file:/a.jar", null));
        first.namespaces.put("n1", new MbtNamespace());

        MbtDocument second = new MbtDocument();
        second.dependencyModules.add(new MbtDependencyModule("a:b:1", "file:/other.jar", "file:/s.jar"));
        second.dependencyModules.add(new MbtDependencyModule("c:d:2", "file:/c.jar", null));
        second.namespaces.put("n2", new MbtNamespace());

        aggregator.add(first);
        aggregator.add(second);

        MbtDocument merged = aggregator.toDocument();
        assertEquals(2, merged.dependencyModules.size());
        assertEquals("file:/a.jar", merged.dependencyModules.get(0).jar);
        assertEquals(2, merged.namespaces.size());
    }
}
