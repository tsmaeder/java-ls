/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.mbt.MbtDependencyModuleInfo;
import ch.castleridge.javals.indexing.mbt.MbtInfo;

class MbtServiceTest {

    @Test
    void sourceJarLookupCollectsDependencyJarPairs(@TempDir Path tempDir) throws Exception {
        Path binJar = tempDir.resolve("lib/dep.jar");
        Path srcJar = tempDir.resolve("lib/dep-sources.jar");
        Files.createDirectories(binJar.getParent());
        Files.write(binJar, new byte[0]);
        Files.write(srcJar, new byte[0]);

        MbtDependencyModuleInfo dep = new MbtDependencyModuleInfo();
        dep.jar = binJar.toUri().toString();
        dep.sources = srcJar.toUri().toString();

        MbtInfo info = new MbtInfo();
        info.dependencyModules = List.of(dep);

        Map<String, String> out = MbtService.sourceJarLookup(info);
        assertEquals(1, out.size());
        assertEquals(srcJar.toUri().toString(), out.get(binJar.toUri().toString()));
    }

    @Test
    void sourceJarLookupSkipsInvalidDependencyPairs(@TempDir Path tempDir) throws Exception {
        Path binJar = tempDir.resolve("lib/dep.jar");
        Files.createDirectories(binJar.getParent());
        Files.write(binJar, new byte[0]);

        MbtDependencyModuleInfo noSources = new MbtDependencyModuleInfo();
        noSources.jar = binJar.toUri().toString();
        noSources.sources = null;

        MbtDependencyModuleInfo missingBinary = new MbtDependencyModuleInfo();
        missingBinary.jar = tempDir.resolve("missing.jar").toUri().toString();
        missingBinary.sources = tempDir.resolve("missing-sources.jar").toUri().toString();

        MbtInfo info = new MbtInfo();
        info.dependencyModules = List.of(noSources, missingBinary);

        Map<String, String> out = MbtService.sourceJarLookup(info);
        assertTrue(out.isEmpty());
    }

    @Test
    void classPathForUsesFirstOwningTargetNotDependent(@TempDir Path tempDir) throws Exception {
        Path libSrc = tempDir.resolve("lib/src");
        Path appSrc = tempDir.resolve("app/src");
        Files.createDirectories(libSrc);
        Files.createDirectories(appSrc);
        Path libFile = libSrc.resolve("Lib.java");
        Files.writeString(libFile, "public class Lib {}\n", StandardCharsets.UTF_8);

        String mbt = """
                {
                  "namespaces": {
                    "app": {
                      "sources": ["app/src"],
                      "dependsOn": ["lib"]
                    },
                    "lib": {
                      "sources": ["lib/src"]
                    }
                  },
                  "dependencyModules": []
                }
                """;
        Files.writeString(tempDir.resolve("mbt.json"), mbt, StandardCharsets.UTF_8);

        MbtService mbtService = new MbtService(null);
        mbtService.loadFrom(tempDir.resolve("mbt.json"), tempDir);

        assertEquals(2, mbtService.targets().size());
        ClasspathOrder libCp = mbtService.targets().get("lib").classpath();
        ClasspathOrder appCp = mbtService.targets().get("app").classpath();
        assertNotSame(libCp, appCp);

        ClasspathOrder forLibFile = mbtService.classPathFor(libFile.toUri().toString());
        assertSame(libCp, forLibFile);

        Path appFile = appSrc.resolve("App.java");
        ClasspathOrder forAppFile = mbtService.classPathFor(appFile.toUri().toString());
        assertSame(appCp, forAppFile);
    }
}
