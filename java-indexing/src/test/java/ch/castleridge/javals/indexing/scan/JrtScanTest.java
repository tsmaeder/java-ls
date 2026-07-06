package ch.castleridge.javals.indexing.scan;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JrtScanTest {

    @Test
    void scanJavaBaseFindsWellKnownTypes() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        TypeEntry object = index.get("java/lang/Object");
        assertNotNull(object, "java.base should index java/lang/Object");
        assertEquals("java/lang/Object", object.jvmName());

        TypeEntry list = index.get("java/util/List");
        assertNotNull(list, "java.base should index java/util/List");
        assertTrue(list.methods().stream().anyMatch(m -> m.name().equals("size")),
                "java/util/List should carry a size() method entry");
        assertTrue(list.methods().stream().anyMatch(m -> m.name().equals("of")),
                "java/util/List should carry one of its static of() factories");
    }

    @Test
    void formalTypeParametersAreExtractedFromClassSignatures() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);

        TypeEntry list = index.get("java/util/List");
        assertNotNull(list);
        assertEquals(1, list.typeParams().size(), "List<E> has one type parameter");
        assertEquals("E", list.typeParams().get(0).name());

        TypeEntry map = index.get("java/util/Map");
        assertNotNull(map);
        assertEquals(2, map.typeParams().size(), "Map<K,V> has two type parameters");
        assertEquals("K", map.typeParams().get(0).name());
        assertEquals("V", map.typeParams().get(1).name());

        TypeEntry function = index.get("java/util/function/Function");
        assertNotNull(function);
        assertEquals(2, function.typeParams().size(), "Function<T,R> has two type parameters");
        assertEquals("T", function.typeParams().get(0).name());
        assertEquals("R", function.typeParams().get(1).name());

        TypeEntry object = index.get("java/lang/Object");
        assertNotNull(object);
        assertTrue(object.typeParams().isEmpty(), "Object is not generic");
    }

    @Test
    void packageInfoAndModuleInfoAreFiltered() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        for (TypeEntry e : index.all()) {
            String jvm = e.jvmName();
            assertTrue(!jvm.endsWith("/module-info") && !jvm.equals("module-info"),
                    "module-info should not be indexed: " + jvm);
            assertTrue(!jvm.endsWith("/package-info") && !jvm.equals("package-info"),
                    "package-info should not be indexed: " + jvm);
        }
    }

    @Test
    void completableFutureImplementsParameterizedCompletionStage() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        TypeEntry cf = index.get("java/util/concurrent/CompletableFuture");
        assertNotNull(cf, "java.base should index CompletableFuture");
        assertEquals(1, cf.typeParams().size());
        String typeParam = cf.typeParams().get(0).name();

        boolean hasParameterizedCompletionStage = cf.interfaceRefs().stream().anyMatch(ref -> {
            if (!(ref instanceof Type.Parameterized p)) return false;
            if (!(p.raw() instanceof TypeRef.Resolved r)) return false;
            if (!r.jvmBinaryName().equals("java/util/concurrent/CompletionStage")) return false;
            if (p.typeArgs().size() != 1) return false;
            return p.typeArgs().get(0) instanceof Type.TypeVariable tv
                    && tv.name().equals(typeParam);
        });
        assertTrue(hasParameterizedCompletionStage,
                "CompletableFuture should implement CompletionStage<" + typeParam + ">");
    }

    @Test
    void scanCapturesJavaBaseModuleEntry() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        ModuleEntry javaBase = index.getModule("java.base");
        assertNotNull(javaBase, "java.base ModuleEntry should be in the index");
        assertEquals("java.base", javaBase.name());

        // java.base exports java.lang to everyone unconditionally.
        assertTrue(javaBase.exports().stream().anyMatch(e ->
                        e.packageJvm().equals("java/lang") && e.toModules().isEmpty()),
                () -> "java.base should unconditionally export java/lang; got: " + javaBase.exports());

        // java.base never `requires` anything else - it's the root.
        assertTrue(javaBase.requires().isEmpty(),
                () -> "java.base should have no requires; got: " + javaBase.requires());
    }

    @Test
    void scanCapturesNonRootModuleRequires() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        List<Throwable> failures = scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        ModuleEntry sql = index.getModule("java.sql");
        assertNotNull(sql, "java.sql ModuleEntry should be in the index");
        assertTrue(sql.requires().stream().anyMatch(r -> r.moduleName().equals("java.base")),
                () -> "java.sql should require java.base; got: " + sql.requires());
        assertTrue(sql.exports().stream().anyMatch(e -> e.packageJvm().equals("java/sql")),
                () -> "java.sql should export java/sql; got: " + sql.exports());
    }

    @Test
    void base64EncoderIsIndexedAsSeparateNestedClass() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);

        TypeEntry encoder = index.get("java/util/Base64$Encoder");
        assertNotNull(encoder, "java.util.Base64.Encoder should be indexed as its own entry");
        assertTrue(encoder.methods().stream().anyMatch(m -> m.name().equals("encodeToString")),
                "Base64.Encoder should carry encodeToString(byte[])");
    }

    @Test
    void minimalCatalogScanRecordsClassFilesWithoutTypeEntries() {
        Index index = new Index();
        Scanner scanner = new Scanner(false);
        List<Throwable> failures = scanner.scanAll(
                List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);
        assertTrue(failures.isEmpty(), () -> "unexpected failures: " + failures);

        assertEquals(0, index.size(), "minimal scan should not produce TypeEntry records");
        assertTrue(index.classFileSize() > 1000, "java.base alone should catalog many classes");

        ClassFileEntry object = index.getAllClassFiles("java/lang/Object").stream().findFirst().orElse(null);
        assertNotNull(object, "java/lang/Object should be cataloged from jrt path");
        assertEquals("java/lang/Object", object.jvmOwnerName());
        assertNull(index.get("java/lang/Object"), "no full TypeEntry for cataloged class");
    }

    @Test
    void listPackageReturnsPackageMembers() {
        Index index = new Index();
        Scanner scanner = new Scanner();
        scanner.scanAll(List.of(new JrtInput(Path.of(System.getProperty("java.home")))), index);

        List<TypeEntry> javaUtil = index.listPackage("java/util", false);
        assertTrue(javaUtil.stream().anyMatch(e -> e.jvmName().equals("java/util/ArrayList")),
                "java/util should contain ArrayList");
        assertTrue(javaUtil.stream().anyMatch(e -> e.jvmName().equals("java/util/HashMap")),
                "java/util should contain HashMap");

        // Bytecode-sourced methods must carry fully resolved TypeRefs.
        TypeEntry arrayList = index.get("java/util/ArrayList");
        assertNotNull(arrayList);
        MethodEntry size = arrayList.methods().stream()
                .filter(m -> m.name().equals("size"))
                .findFirst().orElseThrow();
        assertEquals(Type.Primitive.INT, size.returnType());
        assertTrue(size.paramTypes().isEmpty(), "size() takes no parameters");
    }
}
