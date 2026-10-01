/**
 * Copyright 2026 by Castle Ridge Software GmbH
 */
package ch.castleridge.javals.mavenimporter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputUpToDateTest {

    @TempDir
    Path temp;

    @Test
    void missingOutputIsNotUpToDate() throws Exception {
        Path pom = temp.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        assertFalse(OutputUpToDate.isUpToDate(temp.resolve("missing/mbt.json"), List.of(pom)));
    }

    @Test
    void allPomsOlderOrEqualMeansUpToDate() throws Exception {
        Path pom1 = temp.resolve("a/pom.xml");
        Path pom2 = temp.resolve("b/pom.xml");
        Files.createDirectories(pom1.getParent());
        Files.createDirectories(pom2.getParent());
        Files.writeString(pom1, "<project/>");
        Files.writeString(pom2, "<project/>");

        Path output = temp.resolve("out/mbt.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{}");

        FileTime older = FileTime.from(1_000_000L, TimeUnit.SECONDS);
        FileTime newer = FileTime.from(2_000_000L, TimeUnit.SECONDS);
        Files.setLastModifiedTime(pom1, older);
        Files.setLastModifiedTime(pom2, older);
        Files.setLastModifiedTime(output, newer);

        assertTrue(OutputUpToDate.isUpToDate(output, List.of(pom1, pom2)));

        Files.setLastModifiedTime(pom2, newer);
        assertTrue(OutputUpToDate.isUpToDate(output, List.of(pom1, pom2)));
    }

    @Test
    void anyPomNewerMeansNotUpToDate() throws Exception {
        Path pom = temp.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        Path output = temp.resolve("mbt.json");
        Files.writeString(output, "{}");

        FileTime older = FileTime.from(1_000_000L, TimeUnit.SECONDS);
        FileTime newer = FileTime.from(2_000_000L, TimeUnit.SECONDS);
        Files.setLastModifiedTime(output, older);
        Files.setLastModifiedTime(pom, newer);

        assertFalse(OutputUpToDate.isUpToDate(output, List.of(pom)));
    }

    @Test
    void newerRulesFileMeansNotUpToDate() throws Exception {
        Path pom = temp.resolve("pom.xml");
        Path rules = temp.resolve("rules.json");
        Path output = temp.resolve("mbt.json");
        Files.writeString(pom, "<project/>");
        Files.writeString(rules, "[]");
        Files.writeString(output, "{}");

        FileTime older = FileTime.from(1_000_000L, TimeUnit.SECONDS);
        FileTime newer = FileTime.from(2_000_000L, TimeUnit.SECONDS);
        Files.setLastModifiedTime(pom, older);
        Files.setLastModifiedTime(output, older);
        Files.setLastModifiedTime(rules, newer);

        assertFalse(OutputUpToDate.isUpToDate(output, List.of(pom, rules)));
    }
}
