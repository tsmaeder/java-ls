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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceUrisTest {

    @Test
    void jarEntryCompactsToRelativePath() {
        String jar = "file:///C:/lib/dep.jar";
        String resource = "jar:" + jar + "!/com/example/Hello.class";
        String compact = ResourceUris.compact(resource, jar);
        assertEquals("com/example/Hello.class", compact);
        assertEquals(resource, ResourceUris.resolve(jar, compact));
    }

    @Test
    void jrtEntryCompactsToRelativePath() {
        String jrtHome = "jrt:///C:/jdk";
        String resource = jrtHome + "!/modules/java.base/java/lang/Object.class";
        String compact = ResourceUris.compact(resource, jrtHome);
        assertEquals("modules/java.base/java/lang/Object.class", compact);
        assertEquals(resource, ResourceUris.resolve(jrtHome, compact));
    }

    @Test
    void directoryEntryWithoutTrailingSlashCompacts() {
        String root = "file:///C:/proj/out";
        String resource = "file:///C:/proj/out/com/foo/Bar.class";
        String compact = ResourceUris.compact(resource, root);
        assertEquals("com/foo/Bar.class", compact);
        assertEquals(resource, ResourceUris.resolve(root, compact));
    }

    @Test
    void directoryEntryCompactsToRelativePath() {
        String root = "file:///C:/proj/out/";
        String resource = "file:///C:/proj/out/com/foo/Bar.class";
        String compact = ResourceUris.compact(resource, root);
        assertEquals("com/foo/Bar.class", compact);
        assertEquals(resource, ResourceUris.resolve(root, compact));
    }

    @Test
    void nonRoundTrippableIndexUriKeptAbsolute() {
        String source = "index:///test-classpath/";
        String resource = "index:///com/example/Hello.class";
        String compact = ResourceUris.compact(resource, source);
        assertEquals(resource, compact);
        assertEquals(resource, ResourceUris.resolve(source, compact));
    }

    @Test
    void compactDoesNotInternSharedRelativePaths() {
        String jar = "file:///lib.jar";
        String a = ResourceUris.compact("jar:" + jar + "!/java/lang/Object.class", jar);
        String b = ResourceUris.compact("jar:" + jar + "!/java/lang/Object.class", jar);
        assertEquals(a, b);
        assertNotSame(a, b);
    }

    @Test
    void classFileTypeEntryExposesFullUriWhileStoringRelative() {
        String jar = "file:///lib.jar";
        String resource = "jar:" + jar + "!/com/Foo.class";
        ClassFileTypeEntry entry = new ClassFileTypeEntry(
                resource, jar, "com/Foo", 1,
                null, EmptyArrays.TYPE, EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD, EmptyArrays.METHOD, EmptyArrays.STRING,
                EmptyArrays.TYPE_REF, EmptyArrays.RECORD_COMPONENT, EmptyArrays.ANNOTATION_REF);
        assertEquals("com/Foo.class", entry.resourcePath());
        assertEquals(resource, entry.resourceUri());
        assertNotEquals(entry.resourcePath(), entry.resourceUri());
        assertTrue(entry.resourceUri().contains(jar));
    }
}
