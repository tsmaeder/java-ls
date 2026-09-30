/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path temp;

    @Test
    void resolveOutputDefaultsToDotMetalsMbtJsonMaven() {
        Path directory = temp.resolve("ws").toAbsolutePath().normalize();
        assertEquals(directory.resolve(".metals/mbt.json.maven"), Main.resolveOutput(directory, null));
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
    void emptyWorkspaceExitsZeroWithoutOutput() throws Exception {
        Path workspace = temp.resolve("force-empty");
        Files.createDirectories(workspace);
        assertEquals(0, Main.run(new String[] {workspace.toString(), "--force"}));
        assertEquals(0, Main.run(new String[] {workspace.toString(), "-f"}));
        assertFalse(Files.exists(workspace.resolve(".metals/mbt.json.maven")));
    }

    @Test
    void emptyWorkspaceDeletesStaleOutput() throws Exception {
        Path workspace = temp.resolve("stale");
        Path output = workspace.resolve(".metals/mbt.json.maven");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"namespaces\":{},\"dependencyModules\":[]}");
        assertTrue(Files.isRegularFile(output));

        assertEquals(0, Main.run(new String[] {workspace.toString()}));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsMissingDirectory() {
        assertEquals(1, Main.run(new String[] {temp.resolve("missing").toString()}));
    }

    @Test
    void customRelativeOutputIsAcceptedWhenEmpty() throws Exception {
        Path workspace = temp.resolve("empty-ish");
        Files.createDirectories(workspace);
        Path custom = workspace.resolve("custom/mbt.json");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "{}");
        assertEquals(0, Main.run(new String[] {workspace.toString(), "-o", "custom/mbt.json"}));
        assertFalse(Files.exists(custom));
    }
}
