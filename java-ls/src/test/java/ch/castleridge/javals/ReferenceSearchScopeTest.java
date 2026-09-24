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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReferenceSearchScopeTest {

    private static final ReferenceSearchScope WORKSPACE_ONLY = ReferenceSearchScope.DEFAULT;
    private static final ReferenceSearchScope JARS = new ReferenceSearchScope(true, false);
    private static final ReferenceSearchScope JDK = new ReferenceSearchScope(false, true);

    @Test
    void workspaceSourcesAreAlwaysIncluded() {
        assertTrue(WORKSPACE_ONLY.include(
                "file:///workspace/src/main/java/",
                "com/example/Hello.java"));
        assertTrue(JARS.include("file:///workspace/src/", "com/example/Hello.java"));
        assertTrue(JDK.include("file:///workspace/src/", "com/example/Hello.java"));
    }

    @Test
    void jarsAreIncludedOnlyWhenEnabled() {
        String jar = "file:///lib/dep.jar";
        assertFalse(WORKSPACE_ONLY.include(jar, "com/example/Greeter.class"));
        assertFalse(JDK.include(jar, "com/example/Greeter.class"));
        assertTrue(JARS.include(jar, "com/example/Greeter.class"));
        assertTrue(JARS.include("jar:" + jar, "com/example/Greeter.java"));
    }

    @Test
    void jdkIsIncludedOnlyWhenEnabled() {
        String jrt = "jrt:///C:/jdk-25";
        assertFalse(WORKSPACE_ONLY.include(jrt, "java.base/java/lang/String.class"));
        assertFalse(JARS.include(jrt, "java.base/java/lang/String.class"));
        assertTrue(JDK.include(jrt, "java.base/java/lang/String.class"));
    }
}
