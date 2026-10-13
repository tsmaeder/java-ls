/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class FileUrisTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void underRootIgnoresDriveLetterCase() {
        String root = "file:///D:/workspace/src/";
        String file = "file:///d:/workspace/src/pkg/Main.java";
        assertTrue(FileUris.underRoot(file, root));
        assertFalse(FileUris.underRoot("file:///d:/other/src/Main.java", root));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void underRootIgnoresPercentEncodedDriveColon() {
        String root = "file:///D:/workspace/src/";
        String file = "file:///D%3A/workspace/src/pkg/Main.java";
        assertTrue(FileUris.underRoot(file, root));
    }

    @Test
    void underRootMatchesDecodedPathSegments() {
        String root = "file:///tmp/workspace/src/";
        String file = "file:///tmp/workspace/src/pkg/Main.java";
        assertTrue(FileUris.underRoot(file, root));
        assertTrue(FileUris.underRoot(
                "file:///tmp/workspace/src/pkg/%4Dain.java", root));
        assertFalse(FileUris.underRoot("file:///tmp/other/src/Main.java", root));
    }
}
