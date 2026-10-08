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

    @Test
    void targetForAttachedSourceUsesFirstDependentTarget(@TempDir Path tempDir) throws Exception {
        Path firstSrc = tempDir.resolve("first/src");
        Path secondSrc = tempDir.resolve("second/src");
        Path aloneSrc = tempDir.resolve("alone/src");
        Files.createDirectories(firstSrc);
        Files.createDirectories(secondSrc);
        Files.createDirectories(aloneSrc);
        Path binJar = tempDir.resolve("lib/dep.jar");
        Path srcJar = tempDir.resolve("lib/dep-sources.jar");
        Files.createDirectories(binJar.getParent());
        Files.write(binJar, new byte[0]);
        Files.write(srcJar, new byte[0]);

        String binUri = binJar.toUri().toString();
        String srcUri = srcJar.toUri().toString();
        String mbt = """
                {
                  "namespaces": {
                    "first": {
                      "sources": ["first/src"],
                      "dependencyModules": ["dep"]
                    },
                    "second": {
                      "sources": ["second/src"],
                      "dependencyModules": ["dep"]
                    },
                    "alone": {
                      "sources": ["alone/src"]
                    }
                  },
                  "dependencyModules": [
                    {
                      "id": "dep",
                      "jar": "%s",
                      "sources": "%s"
                    }
                  ]
                }
                """.formatted(binUri, srcUri);
        Files.writeString(tempDir.resolve("mbt.json"), mbt, StandardCharsets.UTF_8);

        MbtService mbtService = new MbtService(null);
        mbtService.loadFrom(tempDir.resolve("mbt.json"), tempDir);

        assertEquals(3, mbtService.targets().size(), () -> "targets=" + mbtService.targets().keySet());
        assertTrue(mbtService.targets().get("first").classpath().contains(binUri));
        assertTrue(mbtService.targets().get("second").classpath().contains(binUri));
        assertTrue(!mbtService.targets().get("alone").classpath().contains(binUri));

        String attached = "jar:" + srcUri + "!/com/example/Lib.java";
        var target = mbtService.targetFor(attached);
        assertTrue(target.isPresent(), "attached source should resolve to a dependent target");
        // First target in namespace-id order whose classpath contains the binary jar.
        assertEquals("first", target.get().id());
        assertSame(mbtService.targets().get("first").classpath(), mbtService.classPathFor(attached));
    }

    @Test
    void targetForOwnSourcesBeatsAttachedDependency(@TempDir Path tempDir) throws Exception {
        Path ownerSrc = tempDir.resolve("owner/src");
        Path consumerSrc = tempDir.resolve("consumer/src");
        Files.createDirectories(ownerSrc);
        Files.createDirectories(consumerSrc);
        Path ownerFile = ownerSrc.resolve("Owned.java");
        Files.writeString(ownerFile, "public class Owned {}\n", StandardCharsets.UTF_8);
        Path binJar = tempDir.resolve("lib/dep.jar");
        Path srcJar = tempDir.resolve("lib/dep-sources.jar");
        Files.createDirectories(binJar.getParent());
        Files.write(binJar, new byte[0]);
        Files.write(srcJar, new byte[0]);

        String mbt = """
                {
                  "namespaces": {
                    "consumer": {
                      "sources": ["consumer/src"],
                      "dependencyModules": ["dep"]
                    },
                    "owner": {
                      "sources": ["owner/src"]
                    }
                  },
                  "dependencyModules": [
                    {
                      "id": "dep",
                      "jar": "%s",
                      "sources": "%s"
                    }
                  ]
                }
                """.formatted(binJar.toUri(), srcJar.toUri());
        Files.writeString(tempDir.resolve("mbt.json"), mbt, StandardCharsets.UTF_8);

        MbtService mbtService = new MbtService(null);
        mbtService.loadFrom(tempDir.resolve("mbt.json"), tempDir);

        // Own source roots win: owner file is not resolved via attached-source dep of consumer.
        assertEquals("owner", mbtService.targetFor(ownerFile.toUri().toString()).orElseThrow().id());
        String attached = "jar:" + srcJar.toUri() + "!/com/example/Lib.java";
        assertEquals("consumer", mbtService.targetFor(attached).orElseThrow().id());
    }
}
