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
package ch.castleridge.javals.analysis;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachedSourceTest {

    @Test
    void mapsBytecodeEntryToSourcesJarJavaEntry(@TempDir Path workspace) {
        String binJarUri = jarUri(workspace, "dep.jar");
        String srcJarUri = jarUri(workspace, "dep-sources.jar");

        Optional<String> uri = AttachedSource.javaUri(
                "jar:" + binJarUri + "!/com/example/Hello.class",
                binJarUri,
                Map.of(binJarUri, srcJarUri));

        assertTrue(uri.isPresent());
        assertEquals("jar:" + srcJarUri + "!/com/example/Hello.java", uri.get());
    }

    @Test
    void mapsNestedBytecodeEntryToOuterSourcesJarJavaEntry(@TempDir Path workspace) {
        String binJarUri = jarUri(workspace, "dep.jar");
        String srcJarUri = jarUri(workspace, "dep-sources.jar");

        Optional<String> uri = AttachedSource.javaUri(
                "jar:" + binJarUri + "!/java/util/Base64$Encoder.class",
                binJarUri,
                Map.of(binJarUri, srcJarUri));

        assertTrue(uri.isPresent());
        assertEquals("jar:" + srcJarUri + "!/java/util/Base64.java", uri.get());
    }

    @Test
    void mapsJrtEntryToJdkSourceZipJavaEntry(@TempDir Path workspace) {
        String jrtUri = "jrt:///C:/jdk-25";
        String srcZipUri = jarUri(workspace, "src.zip");

        Optional<String> uri = AttachedSource.javaUri(
                jrtUri + "!/java.base/java/util/Base64$Encoder.class",
                jrtUri,
                Map.of(jrtUri, srcZipUri));

        assertTrue(uri.isPresent());
        assertEquals("jar:" + srcZipUri + "!/java.base/java/util/Base64.java", uri.get());
    }

    @Test
    void keepsSourceEntriesAsTheyAre() {
        String sourceUri = "file:///workspace/src/main/java/com/example/Hello.java";
        assertEquals(Optional.of(sourceUri),
                AttachedSource.javaUri(sourceUri, "file:///workspace/src/main/java/", Map.of()));
    }

    @Test
    void classFileWithoutAttachedSourcesHasNoNavigableSource(@TempDir Path workspace) {
        String binJarUri = jarUri(workspace, "dep.jar");
        assertEquals(Optional.empty(), AttachedSource.javaUri(
                "jar:" + binJarUri + "!/com/example/Hello.class", binJarUri, Map.of()));
    }

    @Test
    void outerClassJavaEntryStripsNestedSuffix() {
        assertEquals("java/util/Base64.java",
                AttachedSource.outerClassJavaEntry("java/util/Base64$Encoder.class"));
        assertEquals("com/example/Hello.java",
                AttachedSource.outerClassJavaEntry("com/example/Hello.class"));
        assertEquals("pkg/Outer.java",
                AttachedSource.outerClassJavaEntry("pkg/Outer$Inner$Deep.class"));
    }

    private static String jarUri(Path workspace, String name) {
        return workspace.resolve("lib").resolve(name).toAbsolutePath().normalize().toUri().toString();
    }
}
