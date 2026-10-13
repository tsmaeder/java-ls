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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceUrisTest {

    @Test
    void packageRootUriStripsJarPackageAndFile() {
        String resource = "jar:file:///C:/lib/dep.jar!/com/example/Foo.class";
        assertEquals(
                "jar:file:///C:/lib/dep.jar!/",
                ResourceUris.packageRootUri(resource, "com/example"));
    }

    @Test
    void packageRootUriStripsJdkModulePackageAndFile() {
        String resource = "jdk:///C:/jdk!/java.base/java/lang/Object.class";
        assertEquals(
                "jdk:///C:/jdk!/java.base/",
                ResourceUris.packageRootUri(resource, "java/lang"));
    }

    @Test
    void packageRootUriDirectorySourceRoot() {
        String resource = "file:///C:/proj/src/com/foo/Bar.java";
        assertEquals(
                "file:///C:/proj/src/",
                ResourceUris.packageRootUri(resource, "com/foo"));
    }

    @Test
    void packageRootUriDefaultPackageAndModuleInfo() {
        String type = "jar:file:///mod.jar!/Top.class";
        assertEquals("jar:file:///mod.jar!/", ResourceUris.packageRootUri(type, ""));
        String moduleInfo = "jar:file:///mod.jar!/module-info.class";
        assertEquals("jar:file:///mod.jar!/", ResourceUris.packageRootUri(moduleInfo, ""));
        String jdkModuleInfo = "jdk:///C:/jdk!/java.base/module-info.class";
        assertEquals(
                "jdk:///C:/jdk!/java.base/",
                ResourceUris.packageRootUri(jdkModuleInfo, ""));
    }

    @Test
    void packageRootUriNullAndEmpty() {
        assertNull(ResourceUris.packageRootUri(null, "com"));
        assertEquals("", ResourceUris.packageRootUri("", "com"));
    }

    @Test
    void classpathContainerKeepsJarBangInclusive() {
        String jar = "jar:file:///C:/lib/dep.jar!/";
        String resource = jar + "com/example/Hello.class";
        assertEquals(jar, ResourceUris.classpathContainer(resource));
    }

    @Test
    void classpathContainerKeepsJdkBangInclusive() {
        String jdkHome = "jdk:///C:/jdk!/";
        String resource = jdkHome + "java.base/java/lang/Object.class";
        assertEquals(jdkHome, ResourceUris.classpathContainer(resource));
    }

    @Test
    void classpathContainerKeepsDirectoryResource() {
        String resource = "file:///C:/proj/src/com/foo/Bar.java";
        assertEquals(resource, ResourceUris.classpathContainer(resource));
    }

    @Test
    void classpathContainerNullAndEmpty() {
        assertNull(ResourceUris.classpathContainer(null));
        assertNull(ResourceUris.classpathContainer(""));
    }

    @Test
    void resolveConcatenatesJarPrefix() {
        String jar = "jar:file:///C:/lib/dep.jar!/";
        String relative = "com/example/Hello.class";
        assertEquals(jar + relative, ResourceUris.resolve(jar, relative));
    }

    @Test
    void resolveConcatenatesJdkPrefix() {
        String jdkHome = "jdk:///C:/jdk!/";
        String relative = "java.base/java/lang/Object.class";
        assertEquals(jdkHome + relative, ResourceUris.resolve(jdkHome, relative));
    }

    @Test
    void resolveConcatenatesDirectoryPrefix() {
        String root = "file:///C:/proj/out/";
        String relative = "com/foo/Bar.class";
        assertEquals(root + relative, ResourceUris.resolve(root, relative));
    }

    @Test
    void resolveLeavesAbsoluteStoredUriUnchanged() {
        String source = "index:///test-classpath/";
        String resource = "index:///com/example/Hello.class";
        assertEquals(resource, ResourceUris.resolve(source, resource));
    }

    @Test
    void classFileTypeEntryExposesFullUriWhileStoringRelative() {
        String jar = "jar:file:///lib.jar!/";
        String relative = "com/Foo.class";
        ClassFileTypeEntry entry = new ClassFileTypeEntry(
                relative, jar, "com/Foo", 1,
                null, EmptyArrays.TYPE, EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD, EmptyArrays.METHOD, EmptyArrays.STRING,
                EmptyArrays.TYPE_REF, EmptyArrays.RECORD_COMPONENT, EmptyArrays.ANNOTATION_REF);
        assertEquals(relative, entry.resourcePath());
        assertEquals(jar + relative, entry.resourceUri());
        assertNotEquals(entry.resourcePath(), entry.resourceUri());
        assertTrue(entry.resourceUri().startsWith(jar));
    }
}
