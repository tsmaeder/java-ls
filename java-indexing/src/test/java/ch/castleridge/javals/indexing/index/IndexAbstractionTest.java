/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bloom.BloomEntry;
import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Contract coverage for {@link Index} / {@link InMemoryIndex}: interface-typed
 * use and batch merge from a non-{@link InMemoryIndex} engine.
 */
class IndexAbstractionTest {

    @Test
    void interfaceTypedInMemoryIndexServesLookups() {
        Index index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo"));

        assertTrue(index.contains("com/example/Foo"));
        assertEquals(1, index.size());
        assertEquals(1, index.entryCount());
        assertEquals("com/example/Foo",
                ch.castleridge.javals.indexing.IndexTestUtils.get(index, "com/example/Foo").jvmOwnerName());
        assertEquals(1, index.searchTypesBySimpleNamePrefix("Fo", 10).size());
    }

    @Test
    void allWithPredicatePeeksIdentityWithoutRequiringFullScanSemantics() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo", "file:///a/"));
        index.add(sourceType("com/example/Bar", "file:///b/"));

        Collection<TypeEntry> onlyA = index.all((sourceUri, resourcePath) -> "file:///a/".equals(sourceUri));
        assertEquals(1, onlyA.size());
        assertEquals("com/example/Foo", onlyA.iterator().next().jvmOwnerName());

        Collection<TypeEntry> byPath = index.all((sourceUri, resourcePath) ->
                resourcePath != null && resourcePath.endsWith("Bar.java"));
        assertEquals(1, byPath.size());
        assertEquals("com/example/Bar", byPath.iterator().next().jvmOwnerName());
    }

    @Test
    void addAllFromForeignIndexMergesTypesModulesAndBloomsOnce() {
        ListIndex foreign = new ListIndex();
        foreign.add(sourceType("com/example/Bar"));
        foreign.addModule(new ModuleEntry(
                "module-info.class",
                "file:///mod/",
                "com.example.mod",
                null,
                0,
                EmptyArrays.REQUIRES,
                EmptyArrays.EXPORTS,
                EmptyArrays.OPENS,
                EmptyArrays.STRING,
                EmptyArrays.PROVIDES,
                EmptyArrays.STRING,
                null));
        IdentifierBloomFilter bloom = IdentifierBloomFilter.create(List.of("Bar"));
        foreign.registerBloom("file:///", "com/example/Bar.java", bloom);

        InMemoryIndex target = new InMemoryIndex();
        AtomicInteger notifications = new AtomicInteger();
        target.addChangedListener(notifications::incrementAndGet);

        target.addAll(foreign);

        assertEquals(1, notifications.get());
        assertEquals("com/example/Bar",
                ch.castleridge.javals.indexing.IndexTestUtils.get(target, "com/example/Bar").jvmOwnerName());
        assertEquals("com.example.mod", target.getModule("com.example.mod").name());
        IdentifierBloomFilter merged = null;
        for (BloomEntry entry : target.bloomFilters()) {
            if ("file:///com/example/Bar.java".equals(entry.resourceUri())) {
                merged = entry.filter();
                break;
            }
        }
        assertSame(bloom, merged);
    }

    @Test
    void addAllFromInMemoryIndexUsesBlobFastPath() {
        Index source = new InMemoryIndex();
        source.add(sourceType("com/example/Baz"));

        InMemoryIndex target = new InMemoryIndex();
        AtomicInteger notifications = new AtomicInteger();
        target.addChangedListener(notifications::incrementAndGet);
        target.addAll(source);

        assertEquals(1, notifications.get());
        assertEquals("com/example/Baz",
                ch.castleridge.javals.indexing.IndexTestUtils.get(target, "com/example/Baz").jvmOwnerName());
    }

    @Test
    void duplicateJvmNamesAreKeptAndShareDecodedIdentityAcrossLookups() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo", "file:///a/"));
        index.add(sourceType("com/example/Foo", "file:///b/"));
        index.add(sourceType("com/example/util/Bar", "file:///a/"));

        assertEquals(2, index.size());
        assertEquals(3, index.entryCount());

        List<TypeEntry> foos = index.getAll("com/example/Foo");
        assertEquals(2, foos.size());
        assertEquals("file:///a/", foos.get(0).sourceUri());
        assertEquals("file:///b/", foos.get(1).sourceUri());

        List<TypeEntry> pkg = index.listPackage("com/example", false);
        assertEquals(2, pkg.size());
        assertSame(foos.get(0), pkg.get(0));
        assertSame(foos.get(1), pkg.get(1));

        List<TypeEntry> nested = index.listPackage("com", true);
        assertEquals(3, nested.size());

        TypeEntry viaGet = ch.castleridge.javals.indexing.IndexTestUtils.get(index, "com/example/Foo");
        assertSame(foos.get(0), viaGet);
        assertSame(viaGet, ch.castleridge.javals.indexing.IndexTestUtils.get(index, "com/example/Foo"));
    }

    @Test
    void inMemoryMergeRemapsIdsOntoExistingEntries() {
        InMemoryIndex target = new InMemoryIndex();
        target.add(sourceType("com/example/Existing", "file:///target/"));

        InMemoryIndex source = new InMemoryIndex();
        source.add(sourceType("com/example/Foo", "file:///src/a/"));
        source.add(sourceType("com/example/Foo", "file:///src/b/"));
        source.add(sourceType("com/other/Bar", "file:///src/a/"));

        target.addAll(source);

        assertEquals(3, target.size());
        assertEquals(4, target.entryCount());
        assertEquals(2, target.getAll("com/example/Foo").size());
        assertEquals(3, target.listPackage("com/example", false).size());
        assertEquals(1, target.listPackage("com/other", false).size());
        assertEquals("com/example/Existing",
                ch.castleridge.javals.indexing.IndexTestUtils.get(target, "com/example/Existing").jvmOwnerName());
    }

    @Test
    void hasPackageIncludesIntermediateParentsWithoutOwningTypes() {
        InMemoryIndex index = new InMemoryIndex();
        index.add(sourceType("demo/util/Thing"));

        assertTrue(index.hasPackage("demo/util"));
        assertTrue(index.hasPackage("demo"));
        assertFalse(index.hasPackage("demo/other"));
        assertTrue(index.listPackage("demo", false).isEmpty());
        assertEquals(1, index.listPackage("demo/util", false).size());
    }

    @Test
    void hasPackageParentsSurviveInMemoryMerge() {
        InMemoryIndex source = new InMemoryIndex();
        source.add(sourceType("demo/util/Thing"));

        InMemoryIndex target = new InMemoryIndex();
        target.addAll(source);

        assertTrue(target.hasPackage("demo"));
        assertTrue(target.hasPackage("demo/util"));
        assertTrue(target.listPackage("demo", false).isEmpty());
    }

    @Test
    void hasPackageParentsSurviveForeignMerge() {
        ListIndex foreign = new ListIndex();
        foreign.add(sourceType("demo/util/Thing"));

        InMemoryIndex target = new InMemoryIndex();
        target.addAll(foreign);

        assertTrue(target.hasPackage("demo"));
        assertTrue(target.hasPackage("demo/util"));
        assertTrue(target.listPackage("demo", false).isEmpty());
    }

    @Test
    void putResourceReplacesTypesAndBloomsWithOneNotification() {
        InMemoryIndex index = new InMemoryIndex();
        AtomicInteger notifications = new AtomicInteger();
        index.addChangedListener(notifications::incrementAndGet);

        String sourceUri = "file:///src/";
        index.add(sourceTypeAt(sourceUri, "com/example/Foo.java", "com/example/Foo"));
        index.registerBloom(sourceUri, "com/example/Foo.java",
                IdentifierBloomFilter.create(List.of("Foo")));
        index.add(sourceTypeAt(sourceUri, "com/example/Other.java", "com/example/Other"));
        notifications.set(0);

        InMemoryIndex replacement = indexWithExplicitPath(sourceUri, "com/example/Foo.java", "com/example/FooV2");
        replacement.registerBloom(sourceUri, "com/example/Foo.java",
                IdentifierBloomFilter.create(List.of("FooV2")));

        index.putResource(sourceUri, "com/example/Foo.java", replacement);

        assertEquals(1, notifications.get());
        assertFalse(index.contains("com/example/Foo"));
        assertTrue(index.contains("com/example/FooV2"));
        assertTrue(index.contains("com/example/Other"));
        assertEquals(1, index.getAll("com/example/FooV2").size());

        boolean foundFooBloom = false;
        for (BloomEntry entry : index.bloomFilters()) {
            if ("com/example/Foo.java".equals(entry.resourcePath())) {
                foundFooBloom = true;
                assertTrue(entry.filter().mightContain("FooV2"));
                break;
            }
        }
        assertTrue(foundFooBloom);
    }

    @Test
    void putResourceEmptyDeletesTypesAndBlooms() {
        InMemoryIndex index = new InMemoryIndex();
        String sourceUri = "file:///src/";
        index.add(sourceTypeAt(sourceUri, "com/example/Foo.java", "com/example/Foo"));
        index.registerBloom(sourceUri, "com/example/Foo.java",
                IdentifierBloomFilter.create(List.of("Foo")));
        index.add(sourceTypeAt(sourceUri, "com/example/Bar.java", "com/example/Bar"));

        AtomicInteger notifications = new AtomicInteger();
        index.addChangedListener(notifications::incrementAndGet);

        index.putResource(sourceUri, "com/example/Foo.java", new InMemoryIndex());

        assertEquals(1, notifications.get());
        assertFalse(index.contains("com/example/Foo"));
        assertTrue(index.contains("com/example/Bar"));
        assertEquals(1, index.entryCount());
        assertTrue(index.bloomFilters().stream().noneMatch(b ->
                "com/example/Foo.java".equals(b.resourcePath())));
    }

    @Test
    void putResourceTombstonesAreInvisibleToLookups() {
        InMemoryIndex index = new InMemoryIndex();
        String sourceUri = "file:///src/";
        index.add(sourceTypeAt(sourceUri, "com/example/Foo.java", "com/example/Foo"));
        index.putResource(sourceUri, "com/example/Foo.java", new InMemoryIndex());

        assertTrue(index.all().isEmpty());
        assertTrue(index.getAll("com/example/Foo").isEmpty());
        assertTrue(index.listPackage("com/example", false).isEmpty());
        assertTrue(index.searchTypesBySimpleNamePrefix("Foo", 10).isEmpty());
        assertTrue(index.isEmpty());
    }

    private static InMemoryIndex indexWithExplicitPath(String sourceUri, String resourcePath, String jvm) {
        InMemoryIndex index = new InMemoryIndex();
        index.add(sourceTypeAt(sourceUri, resourcePath, jvm));
        return index;
    }

    private static SourceTypeEntry sourceTypeAt(String sourceUri, String resourcePath, String jvmOwnerName) {
        return new SourceTypeEntry(
                resourcePath,
                sourceUri,
                jvmOwnerName,
                0,
                TypeDeclKind.CLASS,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                null);
    }

    private static SourceTypeEntry sourceType(String jvmOwnerName) {
        return sourceType(jvmOwnerName, "file:///" + jvmOwnerName + ".java");
    }

    private static SourceTypeEntry sourceType(String jvmOwnerName, String sourceUri) {
        return new SourceTypeEntry(
                sourceUri + jvmOwnerName + ".java",
                sourceUri,
                jvmOwnerName,
                0,
                TypeDeclKind.CLASS,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                null);
    }

    /** Minimal list-backed {@link Index} used only to exercise the generic merge path. */
    private static final class ListIndex implements Index {
        private final List<TypeEntry> types = new ArrayList<>();
        private final List<ModuleEntry> modules = new ArrayList<>();
        private final List<BloomEntry> blooms = new ArrayList<>();

        @Override
        public void addChangedListener(Runnable listener) {}

        @Override
        public void registerBloom(String sourceUri, String resourcePath, IdentifierBloomFilter filter) {
            blooms.add(new BloomEntry(sourceUri, resourcePath, filter));
        }

        @Override
        public List<BloomEntry> bloomFilters() {
            return List.copyOf(blooms);
        }

        @Override
        public void add(TypeEntry entry) {
            types.add(entry);
        }

        @Override
        public void addAll(Index other) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putResource(String sourceUri, String resourcePath, Index replacement) {
            String compacted = ch.castleridge.javals.indexing.model.ResourceUris.compact(resourcePath, sourceUri);
            types.removeIf(t -> {
                String path = switch (t) {
                    case SourceTypeEntry s -> s.resourcePath();
                    case ClassFileTypeEntry c -> c.resourcePath();
                };
                return Objects.equals(sourceUri, t.sourceUri()) && Objects.equals(compacted, path);
            });
            blooms.removeIf(b -> Objects.equals(sourceUri, b.sourceUri())
                    && Objects.equals(compacted, ch.castleridge.javals.indexing.model.ResourceUris.compact(
                            b.resourcePath(), b.sourceUri())));
            if (replacement != null && !replacement.isEmpty()) {
                types.addAll(replacement.all());
                modules.addAll(replacement.allModules());
                blooms.addAll(replacement.bloomFilters());
            }
        }

        @Override
        public boolean isEmpty() {
            return types.isEmpty() && modules.isEmpty() && blooms.isEmpty();
        }

        @Override
        public List<TypeEntry> getAll(String jvmName) {
            return types.stream().filter(t -> jvmName.equals(t.jvmOwnerName())).toList();
        }

        @Override
        public boolean contains(String jvmName) {
            return !getAll(jvmName).isEmpty();
        }

        @Override
        public boolean hasPackage(String packageJvm) {
            return false;
        }

        @Override
        public List<TypeEntry> listPackage(String packageJvm, boolean recurse) {
            return List.of();
        }

        @Override
        public List<TypeEntry> searchTypesBySimpleNamePrefix(String prefix, int limit) {
            return List.of();
        }

        @Override
        public Collection<TypeEntry> all() {
            return List.copyOf(types);
        }

        @Override
        public Collection<TypeEntry> all(BiPredicate<String, String> filter) {
            if (filter == null) return all();
            List<TypeEntry> out = new ArrayList<>();
            for (TypeEntry t : types) {
                String path = switch (t) {
                    case SourceTypeEntry s -> s.resourcePath();
                    case ClassFileTypeEntry c -> c.resourcePath();
                };
                if (filter.test(t.sourceUri(), path)) out.add(t);
            }
            return List.copyOf(out);
        }

        @Override
        public int size() {
            return (int) types.stream().map(TypeEntry::jvmOwnerName).distinct().count();
        }

        @Override
        public int entryCount() {
            return types.size();
        }

        @Override
        public void addModule(ModuleEntry module) {
            modules.add(module);
        }

        @Override
        public List<ModuleEntry> getAllModules(String moduleName) {
            return modules.stream().filter(m -> moduleName.equals(m.name())).toList();
        }

        @Override
        public ModuleEntry getModule(String moduleName) {
            return getAllModules(moduleName).stream().findFirst().orElse(null);
        }

        @Override
        public Collection<ModuleEntry> allModules() {
            return List.copyOf(modules);
        }

        @Override
        public int moduleCount() {
            return (int) modules.stream().map(ModuleEntry::name).distinct().count();
        }
    }
}
