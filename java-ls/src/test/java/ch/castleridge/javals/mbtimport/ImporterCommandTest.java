/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.mbtimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImporterCommandTest {

    @TempDir
    Path temp;

    @Test
    void jarPreferenceUsesCurrentJava() {
        Path ws = temp.resolve("ws");
        Path out = temp.resolve("out.json");
        Path jar = temp.resolve("importer.jar").toAbsolutePath().normalize();
        List<String> command = ImporterCommand.build(jar.toString(), ws, out, null, temp);
        assertEquals(ImporterCommand.javaExecutable(), command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(jar.toString(), command.get(2));
        assertEquals(ws.toAbsolutePath().normalize().toString(), command.get(3));
        assertEquals("--output", command.get(4));
        assertEquals(out.toAbsolutePath().normalize().toString(), command.get(5));
    }

    @Test
    void jarPreferenceAppendsGeneratedSourceRules() {
        Path ws = temp.resolve("ws");
        Path out = temp.resolve("out.json");
        Path rules = temp.resolve("rules.json");
        Path jar = temp.resolve("importer.jar").toAbsolutePath().normalize();
        List<String> command = ImporterCommand.build(jar.toString(), ws, out, rules, temp);
        assertEquals("--generated-source-rules", command.get(6));
        assertEquals(rules.toAbsolutePath().normalize().toString(), command.get(7));
    }

    @Test
    void quotedJarStillDetected() {
        assertTrue(ImporterCommand.isJar("\"C:\\\\tools\\\\x.JAR\""));
        List<String> command = ImporterCommand.build("'./x.jar'", temp, temp.resolve("o.json"), null, temp);
        assertEquals("-jar", command.get(1));
        assertEquals(temp.resolve("x.jar").normalize().toString(), command.get(2));
    }

    @Test
    void relativeJarResolvesAgainstBase() {
        List<String> command =
                ImporterCommand.build("mavenimporter.jar", temp, temp.resolve("o.json"), null, temp);
        assertEquals(temp.resolve("mavenimporter.jar").normalize().toString(), command.get(2));
    }

    @Test
    void relativeJarLeftUnchangedWhenBaseNull() {
        List<String> command =
                ImporterCommand.build("mavenimporter.jar", temp, temp.resolve("o.json"), null, null);
        assertEquals("mavenimporter.jar", command.get(2));
    }

    @Test
    void shellPreferenceDoesNotUseJarFlag() {
        List<String> command = ImporterCommand.build("/usr/local/bin/import-mbt", temp, temp.resolve("o.json"));
        assertFalse(command.contains("-jar"));
    }
}
