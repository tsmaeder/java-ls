/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.index.Index;

class MbtReloadTest {

    @Test
    void isMbtCandidateMatchesConsumerPathsOnly(@TempDir Path tempDir) {
        Path root = tempDir.resolve("ws");
        assertTrue(WorkspaceBootstrap.isMbtCandidate(root.resolve("mbt.json"), List.of(root)));
        assertTrue(WorkspaceBootstrap.isMbtCandidate(
                root.resolve(".javals/mbt.json"), List.of(root)));
        assertTrue(WorkspaceBootstrap.isMbtCandidate(
                root.resolve(".metals/mbt.json"), List.of(root)));
        assertFalse(WorkspaceBootstrap.isMbtCandidate(
                root.resolve(".javals/mbt.json.maven"), List.of(root)));
        assertFalse(WorkspaceBootstrap.isMbtCandidate(
                root.resolve("other.json"), List.of(root)));
    }

    @Test
    void reloadMbtAppliesIncrementalIndexDelta(@TempDir Path tempDir) throws Exception {
        Path src1 = tempDir.resolve("src1");
        Path src2 = tempDir.resolve("src2");
        Files.createDirectories(src1.resolve("com/example"));
        Files.createDirectories(src2.resolve("com/example"));
        Files.writeString(src1.resolve("com/example/One.java"),
                "package com.example;\npublic class One {}\n", StandardCharsets.UTF_8);
        Files.writeString(src2.resolve("com/example/Two.java"),
                "package com.example;\npublic class Two {}\n", StandardCharsets.UTF_8);

        Path mbt = tempDir.resolve("mbt.json");
        Files.writeString(mbt, mbtJson("src1"), StandardCharsets.UTF_8);

        JavaLanguageServer server = new JavaLanguageServer();
        server.setWorkspaceRootsForTest(List.of(tempDir), tempDir);
        MbtService mbtService = server.getMbtService();
        IndexService indexService = server.getIndexService();

        mbtService.loadFrom(mbt, tempDir);
        indexService.index(mbtService.inputSources(), mbtService.sourceRoots());
        Index index = indexService.index().orElseThrow();
        assertTrue(index.contains("com/example/One"));
        assertFalse(index.contains("com/example/Two"));

        Files.writeString(mbt, mbtJson("src1", "src2"), StandardCharsets.UTF_8);
        server.reloadMbt();

        assertSame(index, indexService.index().orElseThrow());
        assertTrue(index.contains("com/example/One"));
        assertTrue(index.contains("com/example/Two"));
        assertEquals(2, mbtService.sourceRootUris().size());
    }

    @Test
    void reloadRelocatesWhenRootMbtDeleted(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Root.java"), "public class Root {}\n", StandardCharsets.UTF_8);

        Path altSrc = tempDir.resolve("alt");
        Files.createDirectories(altSrc);
        Files.writeString(altSrc.resolve("Alt.java"), "public class Alt {}\n", StandardCharsets.UTF_8);

        Path rootMbt = tempDir.resolve("mbt.json");
        Files.writeString(rootMbt, mbtJson("src"), StandardCharsets.UTF_8);
        Path javals = tempDir.resolve(".javals");
        Files.createDirectories(javals);
        Files.writeString(javals.resolve("mbt.json"), mbtJson("alt"), StandardCharsets.UTF_8);

        JavaLanguageServer server = new JavaLanguageServer();
        server.setWorkspaceRootsForTest(List.of(tempDir), tempDir);
        server.getMbtService().loadFrom(rootMbt, tempDir);
        server.getIndexService().index(
                server.getMbtService().inputSources(),
                server.getMbtService().sourceRoots());
        Index index = server.getIndexService().index().orElseThrow();
        assertTrue(index.contains("Root"));
        assertFalse(index.contains("Alt"));

        Files.delete(rootMbt);
        server.reloadMbt();

        assertSame(index, server.getIndexService().index().orElseThrow());
        assertFalse(index.contains("Root"));
        assertTrue(index.contains("Alt"));
    }

    @Test
    void watchedFilesRouteMbtEventsToReload(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Foo.java"), "public class Foo {}\n", StandardCharsets.UTF_8);
        Path mbt = tempDir.resolve("mbt.json");
        Files.writeString(mbt, mbtJson("src"), StandardCharsets.UTF_8);

        JavaLanguageServer server = new JavaLanguageServer();
        server.setWorkspaceRootsForTest(List.of(tempDir), tempDir);
        assertTrue(server.isMbtCandidateUri(mbt.toUri().toString()));
        assertFalse(server.isMbtCandidateUri(src.resolve("Foo.java").toUri().toString()));

        server.getMbtService().loadFrom(mbt, tempDir);
        server.getIndexService().index(
                server.getMbtService().inputSources(),
                server.getMbtService().sourceRoots());

        Path src2 = tempDir.resolve("src2");
        Files.createDirectories(src2);
        Files.writeString(src2.resolve("Bar.java"), "public class Bar {}\n", StandardCharsets.UTF_8);
        Files.writeString(mbt, mbtJson("src", "src2"), StandardCharsets.UTF_8);

        JavaWorkspaceService workspace = (JavaWorkspaceService) server.getWorkspaceService();
        workspace.didChangeWatchedFiles(new org.eclipse.lsp4j.DidChangeWatchedFilesParams(List.of(
                new FileEvent(mbt.toUri().toString(), FileChangeType.Changed))));

        // Debounce is 400ms; wait for reload.
        Index index = server.getIndexService().index().orElseThrow();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && !index.contains("Bar")) {
            Thread.sleep(50);
        }
        assertTrue(index.contains("Foo"));
        assertTrue(index.contains("Bar"));
    }

    private static String mbtJson(String... sourceDirs) {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < sourceDirs.length; i++) {
            if (i > 0) sources.append(", ");
            sources.append('"').append(sourceDirs[i].replace('\\', '/')).append('"');
        }
        return """
                {
                  "namespaces": {
                    "app": {
                      "sources": [%s]
                    }
                  },
                  "dependencyModules": []
                }
                """.formatted(sources);
    }
}
