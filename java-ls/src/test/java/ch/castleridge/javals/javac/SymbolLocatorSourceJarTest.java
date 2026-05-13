package ch.castleridge.javals.javac;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolLocatorSourceJarTest {

    @Test
    void sourceResourceUriMapsBytecodeEntryToSourcesJarJavaEntry(@TempDir Path workspace) {
        Path binJar = workspace.resolve("lib/dep.jar");
        Path srcJar = workspace.resolve("lib/dep-sources.jar");
        String binJarUri = binJar.toAbsolutePath().normalize().toUri().toString();
        String srcJarUri = srcJar.toAbsolutePath().normalize().toUri().toString();
        String classEntry = "jar:" + binJarUri + "!/com/example/Hello.class";
        String wantJava = "jar:" + srcJarUri + "!/com/example/Hello.java";

        TypeEntry entry = new TypeEntry(
                classEntry,
                binJarUri,
                "com/example/Hello",
                0,
                TypeRef.resolved("java/lang/Object"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);

        Optional<String> uri = SymbolLocator.sourceResourceUri(
                entry, Map.of(binJarUri, srcJarUri));
        assertTrue(uri.isPresent());
        assertEquals(wantJava, uri.get());
    }
}
