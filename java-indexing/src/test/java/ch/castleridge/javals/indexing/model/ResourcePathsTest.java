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
package ch.castleridge.javals.indexing.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ResourcePathsTest {

    @Test
    void classfileDefaultIsOwnerPlusClass() {
        assertEquals("com/example/Hello.class",
                ResourcePaths.defaultPath("com/example/Hello", ResourcePaths.Kind.CLASSFILE));
        assertEquals("pkg/Outer$Inner.class",
                ResourcePaths.defaultPath("pkg/Outer$Inner", ResourcePaths.Kind.CLASSFILE));
    }

    @Test
    void sourceDefaultUsesOutermostType() {
        assertEquals("com/example/Hello.java",
                ResourcePaths.defaultPath("com/example/Hello", ResourcePaths.Kind.SOURCE));
        assertEquals("pkg/Outer.java",
                ResourcePaths.defaultPath("pkg/Outer$Inner", ResourcePaths.Kind.SOURCE));
        assertEquals("pkg/Outer.java",
                ResourcePaths.defaultPath("pkg/Outer$Inner$Deep", ResourcePaths.Kind.SOURCE));
    }

    @Test
    void outermostJvmNameTruncatesAtFirstDollar() {
        assertEquals("pkg/Outer", ResourcePaths.outermostJvmName("pkg/Outer$Inner$Deep"));
        assertEquals("pkg/Outer", ResourcePaths.outermostJvmName("pkg/Outer"));
        assertNull(ResourcePaths.outermostJvmName(null));
    }

    @Test
    void forStorageOmitsDefaultPaths() {
        assertNull(ResourcePaths.forStorage(
                "com/example/Hello.class", "com/example/Hello", ResourcePaths.Kind.CLASSFILE));
        assertNull(ResourcePaths.forStorage(
                "pkg/Outer.java", "pkg/Outer$Inner", ResourcePaths.Kind.SOURCE));
    }

    @Test
    void forStorageKeepsMismatchedPaths() {
        // Secondary top-level type living in another type's source file.
        assertEquals("pkg/Foo.java", ResourcePaths.forStorage(
                "pkg/Foo.java", "pkg/Helper", ResourcePaths.Kind.SOURCE));
        // Multi-release classfile layout.
        assertEquals("META-INF/versions/11/com/Foo.class", ResourcePaths.forStorage(
                "META-INF/versions/11/com/Foo.class", "com/Foo", ResourcePaths.Kind.CLASSFILE));
    }

    @Test
    void effectiveRestoresOmittedDefaults() {
        assertEquals("com/example/Hello.class",
                ResourcePaths.effective(null, "com/example/Hello", ResourcePaths.Kind.CLASSFILE));
        assertEquals("pkg/Outer.java",
                ResourcePaths.effective(null, "pkg/Outer$Inner$Deep", ResourcePaths.Kind.SOURCE));
        assertEquals("pkg/Foo.java",
                ResourcePaths.effective("pkg/Foo.java", "pkg/Helper", ResourcePaths.Kind.SOURCE));
    }
}
