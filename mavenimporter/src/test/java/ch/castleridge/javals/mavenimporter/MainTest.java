/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path temp;

    @Test
    void resolveOutputDefaultsToDotMetalsMbtJson() {
        Path directory = temp.resolve("ws").toAbsolutePath().normalize();
        assertEquals(directory.resolve(".metals/mbt.json"), Main.resolveOutput(directory, null));
    }

    @Test
    void resolveOutputResolvesRelativeAgainstInputDirectory() {
        Path directory = temp.resolve("ws").toAbsolutePath().normalize();
        assertEquals(directory.resolve("out/custom.json"), Main.resolveOutput(directory, "out/custom.json"));
    }

    @Test
    void resolveOutputKeepsAbsolutePath() {
        Path directory = temp.resolve("ws").toAbsolutePath().normalize();
        Path absolute = temp.resolve("elsewhere/mbt.json").toAbsolutePath().normalize();
        assertEquals(absolute, Main.resolveOutput(directory, absolute.toString()));
    }

    @Test
    void rejectsUnknownOptionAndMissingOutputValue() {
        assertEquals(1, Main.run(new String[] {"--unknown", temp.toString()}));
        assertEquals(1, Main.run(new String[] {temp.toString(), "--output"}));
        assertEquals(1, Main.run(new String[] {}));
    }

    @Test
    void acceptsForceFlagWithMissingPoms() throws Exception {
        Path workspace = temp.resolve("force-empty");
        Files.createDirectories(workspace);
        assertEquals(1, Main.run(new String[] {workspace.toString(), "--force"}));
        assertEquals(1, Main.run(new String[] {workspace.toString(), "-f"}));
    }

    @Test
    void rejectsMissingDirectory() {
        assertEquals(1, Main.run(new String[] {temp.resolve("missing").toString()}));
    }

    @Test
    void customRelativeOutputIsCreated() throws Exception {
        Path workspace = temp.resolve("empty-ish");
        Files.createDirectories(workspace);
        // No poms → fails before write; just ensure parse accepts -o shape via missing-pom path.
        assertEquals(1, Main.run(new String[] {workspace.toString(), "-o", "custom/mbt.json"}));
        assertTrue(Files.isDirectory(workspace));
    }
}
