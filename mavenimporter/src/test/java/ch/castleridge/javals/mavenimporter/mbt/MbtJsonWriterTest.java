/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter.mbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MbtJsonWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesMetalsDialectUnderDotMetals() throws Exception {
        MbtDocument document = new MbtDocument();
        document.dependencyModules.add(new MbtDependencyModule(
                "com.google.code.gson:gson:2.11.0",
                "file:/repo/gson-2.11.0.jar",
                "file:/repo/gson-2.11.0-sources.jar"));
        MbtNamespace ns = new MbtNamespace();
        ns.sources.add(temp.resolve("src/main/java").toString());
        ns.javacOptions.add("-release");
        ns.javacOptions.add("25");
        ns.dependencyModules.add("com.google.code.gson:gson:2.11.0");
        ns.javaHome = "C:/jdk";
        ns.projectPath = temp.toString();
        ns.classDirectories.add(temp.resolve("target/classes").toString());
        document.namespaces.put("g:a:1.0", ns);

        Path written = MbtJsonWriter.write(temp, document);
        assertEquals(temp.resolve(".metals/mbt.json"), written);
        String json = Files.readString(written);
        assertTrue(json.contains("\"javacOptions\""));
        assertTrue(json.contains("\"classDirectories\""));
        assertTrue(json.contains("\"projectPath\""));
        assertTrue(json.contains("com.google.code.gson:gson:2.11.0"));
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
