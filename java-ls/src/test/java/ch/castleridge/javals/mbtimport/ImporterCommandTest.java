/**
 * Copyright 2026 by Castle Ridge Software GmbH
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
        List<String> command = ImporterCommand.build("/opt/tools/importer.jar", ws, out);
        assertEquals(ImporterCommand.javaExecutable(), command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals("/opt/tools/importer.jar", command.get(2));
        assertEquals(ws.toAbsolutePath().normalize().toString(), command.get(3));
        assertEquals("--output", command.get(4));
        assertEquals(out.toAbsolutePath().normalize().toString(), command.get(5));
    }

    @Test
    void jarPreferenceAppendsGeneratedSourceRules() {
        Path ws = temp.resolve("ws");
        Path out = temp.resolve("out.json");
        Path rules = temp.resolve("rules.json");
        List<String> command = ImporterCommand.build("/opt/tools/importer.jar", ws, out, rules);
        assertEquals("--generated-source-rules", command.get(6));
        assertEquals(rules.toAbsolutePath().normalize().toString(), command.get(7));
    }

    @Test
    void quotedJarStillDetected() {
        assertTrue(ImporterCommand.isJar("\"C:\\\\tools\\\\x.JAR\""));
        List<String> command = ImporterCommand.build("'./x.jar'", temp, temp.resolve("o.json"));
        assertEquals("-jar", command.get(1));
        assertEquals("./x.jar", command.get(2));
    }

    @Test
    void shellPreferenceDoesNotUseJarFlag() {
        List<String> command = ImporterCommand.build("/usr/local/bin/import-mbt", temp, temp.resolve("o.json"));
        assertFalse(command.contains("-jar"));
    }
}
