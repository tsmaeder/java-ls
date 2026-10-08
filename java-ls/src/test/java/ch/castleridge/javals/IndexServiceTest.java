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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexServiceTest {

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
}
