package ch.castleridge.javals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.mbt.MbtDependencyModuleInfo;
import ch.castleridge.javals.indexing.mbt.MbtInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexServiceTest {

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

        Map<String, String> out = IndexService.sourceJarLookup(info);
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

        Map<String, String> out = IndexService.sourceJarLookup(info);
        assertTrue(out.isEmpty());
    }
}
