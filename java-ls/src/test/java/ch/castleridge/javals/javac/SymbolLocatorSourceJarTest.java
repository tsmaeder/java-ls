package ch.castleridge.javals.javac;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.IndexedClassRef;
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

        TypeEntry entry = new ClassFileTypeEntry(
                classEntry,
                binJarUri,
                "com/example/Hello",
                0,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);

        Optional<String> uri = SymbolLocator.sourceResourceUri(
                IndexedClassRef.from(entry), Map.of(binJarUri, srcJarUri));
        assertTrue(uri.isPresent());
        assertEquals(wantJava, uri.get());
    }

    @Test
    void sourceResourceUriMapsNestedBytecodeEntryToOuterSourcesJarJavaEntry(@TempDir Path workspace) {
        Path binJar = workspace.resolve("lib/dep.jar");
        Path srcJar = workspace.resolve("lib/dep-sources.jar");
        String binJarUri = binJar.toAbsolutePath().normalize().toUri().toString();
        String srcJarUri = srcJar.toAbsolutePath().normalize().toUri().toString();
        String classEntry = "jar:" + binJarUri + "!/java/util/Base64$Encoder.class";
        String wantJava = "jar:" + srcJarUri + "!/java/util/Base64.java";

        TypeEntry entry = new ClassFileTypeEntry(
                classEntry,
                binJarUri,
                "java/util/Base64$Encoder",
                0,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.ANNOTATION_REF);

        Optional<String> uri = SymbolLocator.sourceResourceUri(
                IndexedClassRef.from(entry), Map.of(binJarUri, srcJarUri));
        assertTrue(uri.isPresent());
        assertEquals(wantJava, uri.get());
    }

    @Test
    void sourceResourceUriMapsMinimalClassFileEntryToSourcesJarJavaEntry(@TempDir Path workspace) {
        Path binJar = workspace.resolve("lib/dep.jar");
        Path srcJar = workspace.resolve("lib/dep-sources.jar");
        String binJarUri = binJar.toAbsolutePath().normalize().toUri().toString();
        String srcJarUri = srcJar.toAbsolutePath().normalize().toUri().toString();
        String classEntry = "jar:" + binJarUri + "!/com/example/Hello.class";
        String wantJava = "jar:" + srcJarUri + "!/com/example/Hello.java";

        ClassFileEntry entry = new ClassFileEntry(classEntry, binJarUri, "com/example/Hello");

        Optional<String> uri = SymbolLocator.sourceResourceUri(
                IndexedClassRef.from(entry), Map.of(binJarUri, srcJarUri));
        assertTrue(uri.isPresent());
        assertEquals(wantJava, uri.get());
    }

    @Test
    void asClassRefReadsMinimalRealClassFileObject() {
        ClassFileEntry entry = new ClassFileEntry(
                "jar:file:///lib.jar!/com/example/Hello.class",
                "file:///lib.jar",
                "com/example/Hello");
        IndexedClassRef ref = IndexFileManager.asClassRef(
                ch.castleridge.javals.indexing.index.RealClassFileObject.from(entry));
        assertEquals(IndexedClassRef.from(entry), ref);
    }

    @Test
    void outerClassJavaEntryStripsNestedSuffix() {
        assertEquals("java/util/Base64.java",
                SymbolLocator.outerClassJavaEntry("java/util/Base64$Encoder.class"));
        assertEquals("com/example/Hello.java",
                SymbolLocator.outerClassJavaEntry("com/example/Hello.class"));
    }
}
