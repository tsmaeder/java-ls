/**
 * Copyright 2026 by Anysphere Inc.
 *
 * Licensed under the MIT License.
 *
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.index.Index;

class IndexServiceWatchedFilesTest {

    @Test
    void resolveUnderSourceRootsPicksLongestMatchingRoot(@TempDir Path tempDir) {
        Path outer = tempDir.resolve("src").toAbsolutePath().normalize();
        Path nested = outer.resolve("nested").toAbsolutePath().normalize();
        List<IndexService.SourceRoot> roots = List.of(
                new IndexService.SourceRoot(outer, outer.toUri().toString()),
                new IndexService.SourceRoot(nested, nested.toUri().toString()));

        Path file = nested.resolve("com/Foo.java");
        IndexService.ResolvedResource resolved = IndexService.resolveUnderSourceRoots(file, roots);
        assertNotNull(resolved);
        assertEquals(nested.toUri().toString(), resolved.sourceUri());
        assertEquals("com/Foo.java", resolved.relativePath());
    }

    @Test
    void watchedFileChangeUpdatesAndDeletesIndexEntries(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src/main/java");
        Files.createDirectories(src.resolve("com/example"));
        Path foo = src.resolve("com/example/Foo.java");
        Files.writeString(foo, "package com.example;\npublic class Foo {}\n", StandardCharsets.UTF_8);

        String mbt = """
                {
                  "namespaces": {
                    "app": {
                      "sources": ["src/main/java"]
                    }
                  },
                  "dependencyModules": []
                }
                """;
        Files.writeString(tempDir.resolve("mbt.json"), mbt, StandardCharsets.UTF_8);

        IndexService service = new IndexService(null);
        InitializeParams params = new InitializeParams();
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(tempDir.toUri().toString());
        folder.setName("ws");
        params.setWorkspaceFolders(List.of(folder));
        service.initialize(params).get(60, TimeUnit.SECONDS);

        Index index = service.index().orElseThrow();
        assertTrue(index.contains("com/example/Foo"));
        assertEquals(1, service.sourceRootUris().size());
        assertTrue(service.sourceRootUris().get(0).contains("src"));

        Files.writeString(foo, "package com.example;\npublic class Foo { void bar() {} }\n",
                StandardCharsets.UTF_8);
        service.onWatchedFilesChanged(List.of(
                new FileEvent(foo.toUri().toString(), FileChangeType.Changed)))
                .get(30, TimeUnit.SECONDS);
        assertTrue(index.contains("com/example/Foo"));
        assertEquals(1, index.getAll("com/example/Foo").size());

        Path bar = src.resolve("com/example/Bar.java");
        Files.writeString(bar, "package com.example;\npublic class Bar {}\n", StandardCharsets.UTF_8);
        service.onWatchedFilesChanged(List.of(
                new FileEvent(bar.toUri().toString(), FileChangeType.Created)))
                .get(30, TimeUnit.SECONDS);
        assertTrue(index.contains("com/example/Bar"));

        Files.delete(foo);
        service.onWatchedFilesChanged(List.of(
                new FileEvent(foo.toUri().toString(), FileChangeType.Deleted)))
                .get(30, TimeUnit.SECONDS);
        assertFalse(index.contains("com/example/Foo"));
        assertTrue(index.contains("com/example/Bar"));

        // Outside source roots is ignored.
        Path other = tempDir.resolve("Other.java");
        Files.writeString(other, "public class Other {}\n", StandardCharsets.UTF_8);
        service.onWatchedFilesChanged(List.of(
                new FileEvent(other.toUri().toString(), FileChangeType.Created)))
                .get(30, TimeUnit.SECONDS);
        assertFalse(index.contains("Other"));
    }
}
