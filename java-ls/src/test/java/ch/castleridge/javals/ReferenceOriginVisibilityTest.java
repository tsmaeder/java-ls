/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.classpath.UriClasspathEntry;

class ReferenceOriginVisibilityTest {

    private static final String LIB_ROOT = "file:///workspace/lib/src/";
    private static final String APP_ROOT = "file:///workspace/app/src/";
    private static final String OTHER_ROOT = "file:///workspace/other/src/";
    private static final String DEP_JAR = "file:///deps/lib.jar";

    private static final ClasspathOrder APP_CP = new ClasspathOrder(List.of(
            UriClasspathEntry.of(APP_ROOT),
            UriClasspathEntry.of(LIB_ROOT),
            UriClasspathEntry.of(DEP_JAR)), false);

    @Test
    void originContainerResolvesDirectoryToSourceRoot() {
        String origin = LIB_ROOT + "com/example/Greeter.java";
        assertEquals(LIB_ROOT, ReferenceOriginVisibility.originContainer(APP_CP, origin));
    }

    @Test
    void originContainerResolvesJarResourceToJarEntry() {
        String origin = "jar:" + DEP_JAR + "!/com/example/Api.class";
        assertEquals(DEP_JAR, ReferenceOriginVisibility.originContainer(APP_CP, origin));
    }

    @Test
    void originContainerUnknownOriginIsNull() {
        assertNull(ReferenceOriginVisibility.originContainer(APP_CP, null));
        assertNull(ReferenceOriginVisibility.originContainer(APP_CP, ""));
    }

    @Test
    void directoryOriginVisibleOnClasspathThatIncludesItsRoot() {
        String container = ReferenceOriginVisibility.originContainer(
                APP_CP, LIB_ROOT + "com/example/Greeter.java");
        assertTrue(ReferenceOriginVisibility.candidateCanSeeOrigin(APP_CP, container));
    }

    @Test
    void directoryOriginInvisibleOnUnrelatedClasspath() {
        ClasspathOrder otherCp = new ClasspathOrder(List.of(
                UriClasspathEntry.of(OTHER_ROOT)), false);
        String container = ReferenceOriginVisibility.originContainer(
                APP_CP, LIB_ROOT + "com/example/Greeter.java");
        assertFalse(ReferenceOriginVisibility.candidateCanSeeOrigin(otherCp, container));
    }

    @Test
    void jarOriginVisibleWhenJarIsOnClasspath() {
        String container = ReferenceOriginVisibility.originContainer(
                APP_CP, "jar:" + DEP_JAR + "!/com/example/Api.class");
        assertTrue(ReferenceOriginVisibility.candidateCanSeeOrigin(APP_CP, container));
    }

    @Test
    void jarOriginInvisibleWhenJarMissing() {
        ClasspathOrder cp = new ClasspathOrder(List.of(
                UriClasspathEntry.of(APP_ROOT)), false);
        String container = ReferenceOriginVisibility.originContainer(
                APP_CP, "jar:" + DEP_JAR + "!/com/example/Api.class");
        assertFalse(ReferenceOriginVisibility.candidateCanSeeOrigin(cp, container));
    }

    @Test
    void unknownOriginIsNotFiltered() {
        ClasspathOrder empty = new ClasspathOrder(List.of(), false);
        assertTrue(ReferenceOriginVisibility.candidateCanSeeOrigin(empty, null));
    }

    @Test
    void unrestrictedClasspathSeesEveryOrigin() {
        String container = ReferenceOriginVisibility.originContainer(
                APP_CP, LIB_ROOT + "com/example/Greeter.java");
        assertTrue(ReferenceOriginVisibility.candidateCanSeeOrigin(
                ClasspathOrder.UNRESTRICTED, container));
    }
}
