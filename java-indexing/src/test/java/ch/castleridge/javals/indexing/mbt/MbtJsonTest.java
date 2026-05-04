package ch.castleridge.javals.indexing.mbt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.scan.DirInput;
import ch.castleridge.javals.indexing.scan.InputSource;
import ch.castleridge.javals.indexing.scan.JarInput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MbtJsonTest {

    @Test
    void readMinimalFixture() throws IOException {
        Path fixture = resourcePath("mbt/minimal.json");
        MbtInfo info = MbtJson.read(fixture);
        assertNotNull(info.namespaces);
        assertEquals(1, info.namespaces.size());
        MbtTargetInfo main = info.namespaces.get("org.example:demo:1.0:main");
        assertNotNull(main);
        assertEquals(List.of("-source", "21"), main.compilerOptions);
        assertEquals(List.of("src/main/java"), main.sources);
        assertEquals(List.of("target/classes"), main.classes);
        assertNotNull(info.dependencyModules);
        assertTrue(info.dependencyModules.isEmpty());
    }

    @Test
    void toInputSourcesResolvesRelativePathsAndDependencyUris(@TempDir Path workspace) throws IOException {
        Path sources = workspace.resolve("src/main/java");
        Files.createDirectories(sources);
        Path classes = workspace.resolve("target/classes");
        Files.createDirectories(classes);
        Path depJar = workspace.resolve("lib/dep.jar");
        Files.createDirectories(depJar.getParent());
        writeEmptyJar(depJar);

        String mbtBody = """
                {
                  "namespaces": {
                    "org.example:demo:1.0:main": {
                      "sources": ["src/main/java"],
                      "classes": ["target/classes"],
                      "dependencyModules": ["dep:main"]
                    }
                  },
                  "dependencyModules": [
                    {
                      "id": "dep:main",
                      "jar": "%s"
                    }
                  ]
                }
                """.formatted(depJar.toUri());
        Path mbt = workspace.resolve("mbt.json");
        Files.writeString(mbt, mbtBody);

        MbtInfo info = MbtJson.read(mbt);
        List<InputSource> sourcesOut = MbtJson.toInputSources(info, workspace);

        assertEquals(3, sourcesOut.size());
        assertEquals(sources.toAbsolutePath().normalize(), ((DirInput) sourcesOut.get(0)).root());
        assertEquals(classes.toAbsolutePath().normalize(), ((DirInput) sourcesOut.get(1)).root());
        assertEquals(depJar.toAbsolutePath().normalize(), ((JarInput) sourcesOut.get(2)).jar());
    }

    @Test
    void toInputSourcesFromClasspathFixture(@TempDir Path workspace) throws IOException {
        Path sources = workspace.resolve("src/main/java");
        Files.createDirectories(sources);
        Files.copy(resourcePath("mbt/minimal.json"), workspace.resolve("mbt.json"));
        MbtInfo info = MbtJson.read(workspace.resolve("mbt.json"));
        Path classes = workspace.resolve("target/classes");
        Files.createDirectories(classes);
        List<InputSource> out = MbtJson.toInputSources(info, workspace);
        assertEquals(2, out.size());
        assertEquals(sources.toAbsolutePath().normalize(), ((DirInput) out.get(0)).root());
        assertEquals(classes.toAbsolutePath().normalize(), ((DirInput) out.get(1)).root());
    }

    private static Path resourcePath(String name) {
        String cp = name.replace('/', java.io.File.separatorChar);
        InputStream in = MbtJsonTest.class.getClassLoader().getResourceAsStream(cp);
        if (in == null) {
            throw new IllegalStateException("missing test resource: " + name);
        }
        try (in) {
            Path tmp = Files.createTempFile("mbt-resource-", ".json");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeEmptyJar(Path jar) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new ZipEntry("META-INF/"));
            jos.closeEntry();
        }
    }
}
