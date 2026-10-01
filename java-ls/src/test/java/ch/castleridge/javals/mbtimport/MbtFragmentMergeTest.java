/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mbtimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MbtFragmentMergeTest {

    @TempDir
    Path temp;

    @Test
    void mergesNewerFragmentIntoMergedFile() throws Exception {
        Path workspace = temp.resolve("ws");
        Path fragment = BuildSystems.fragmentPath(workspace, "maven");
        Files.createDirectories(fragment.getParent());
        Files.writeString(fragment, """
                {
                  "namespaces": {
                    "a:b:1": {
                      "sources": ["/src"],
                      "javacOptions": ["-source", "21"]
                    }
                  },
                  "dependencyModules": [
                    { "id": "dep:1", "jar": "file:///dep.jar" }
                  ]
                }
                """);

        List<String> logs = new ArrayList<>();
        MbtFragmentMerge.mergeIfNeeded(workspace, BuildSystems.all(), (t, m) -> logs.add(m));

        Path merged = BuildSystems.mergedPath(workspace);
        assertTrue(Files.isRegularFile(merged));
        JsonObject doc = JsonParser.parseString(Files.readString(merged)).getAsJsonObject();
        assertTrue(doc.getAsJsonObject("namespaces").has("a:b:1"));
        assertEquals(
                "21",
                doc.getAsJsonObject("namespaces")
                        .getAsJsonObject("a:b:1")
                        .getAsJsonArray("javacOptions")
                        .get(1)
                        .getAsString());
        assertEquals(1, doc.getAsJsonArray("dependencyModules").size());
    }

    @Test
    void skipsWhenMergedIsUpToDate() throws Exception {
        Path workspace = temp.resolve("ws");
        Path fragment = BuildSystems.fragmentPath(workspace, "maven");
        Path merged = BuildSystems.mergedPath(workspace);
        Files.createDirectories(fragment.getParent());
        Files.writeString(fragment, """
                {"namespaces":{"a":{}},"dependencyModules":[]}
                """);
        Files.writeString(merged, """
                {"namespaces":{"a":{}},"dependencyModules":[]}
                """);

        long base = System.currentTimeMillis();
        Files.setLastModifiedTime(fragment, FileTime.fromMillis(base));
        Files.setLastModifiedTime(merged, FileTime.fromMillis(base + 1_000));

        AtomicInteger writes = new AtomicInteger();
        MbtFragmentMerge.mergeIfNeeded(workspace, BuildSystems.all(), (t, m) -> {
            if (m.startsWith("Wrote ")) {
                writes.incrementAndGet();
            }
        });
        assertEquals(0, writes.get());
        assertEquals(base + 1_000, Files.getLastModifiedTime(merged).toMillis());
    }

    @Test
    void unionsTwoFragmentsPreservingFirstDependencyId() throws Exception {
        Path workspace = temp.resolve("ws");
        Path maven = BuildSystems.fragmentPath(workspace, "maven");
        Files.createDirectories(maven.getParent());
        Files.writeString(maven, """
                {
                  "namespaces": { "maven:ns": { "javacOptions": ["-X"] } },
                  "dependencyModules": [
                    { "id": "shared", "jar": "file:///from-maven.jar" }
                  ]
                }
                """);

        // Simulate a second system fragment path without registering it in BuildSystems.all():
        // write via mergeFragments directly.
        Path other = workspace.resolve(".javals/mbt.json.other");
        Files.writeString(other, """
                {
                  "namespaces": { "other:ns": { "sources": ["/o"] } },
                  "dependencyModules": [
                    { "id": "shared", "jar": "file:///from-other.jar" },
                    { "id": "only-other", "jar": "file:///other.jar" }
                  ]
                }
                """);

        JsonObject merged = MbtFragmentMerge.mergeFragments(List.of(maven, other));
        assertTrue(merged.getAsJsonObject("namespaces").has("maven:ns"));
        assertTrue(merged.getAsJsonObject("namespaces").has("other:ns"));
        assertEquals(2, merged.getAsJsonArray("dependencyModules").size());
        assertEquals(
                "file:///from-maven.jar",
                merged.getAsJsonArray("dependencyModules").get(0).getAsJsonObject().get("jar").getAsString());
    }

    @Test
    void deletesMergedWhenNoFragmentsRemain() throws Exception {
        Path workspace = temp.resolve("ws");
        Path merged = BuildSystems.mergedPath(workspace);
        Files.createDirectories(merged.getParent());
        Files.writeString(merged, "{\"namespaces\":{},\"dependencyModules\":[]}");

        MbtFragmentMerge.mergeIfNeeded(workspace, BuildSystems.all(), (t, m) -> {});
        assertFalse(Files.exists(merged));
    }

    @Test
    void needsMergeWhenMergedMissing() throws Exception {
        Path fragment = temp.resolve("f.json");
        Files.writeString(fragment, "{}");
        assertTrue(MbtFragmentMerge.needsMerge(temp.resolve("missing.json"), List.of(fragment)));
    }
}
