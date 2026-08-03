package ch.castleridge.javals.indexing.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
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
        assertEquals("com/example/Foo", index.get("com/example/Foo").jvmOwnerName());
        assertEquals(1, index.searchTypesBySimpleNamePrefix("Fo", 10).size());
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
        foreign.registerBloom("file:///com/example/Bar.java", bloom);

        InMemoryIndex target = new InMemoryIndex();
        AtomicInteger notifications = new AtomicInteger();
        target.addChangedListener(notifications::incrementAndGet);

        target.addAll(foreign);

        assertEquals(1, notifications.get());
        assertEquals("com/example/Bar", target.get("com/example/Bar").jvmOwnerName());
        assertEquals("com.example.mod", target.getModule("com.example.mod").name());
        assertSame(bloom, target.bloomFilters().get("file:///com/example/Bar.java"));
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
        assertEquals("com/example/Baz", target.get("com/example/Baz").jvmOwnerName());
    }

    private static SourceTypeEntry sourceType(String jvmOwnerName) {
        return new SourceTypeEntry(
                "file:///" + jvmOwnerName + ".java",
                "file:///" + jvmOwnerName + ".java",
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
        private final Map<String, IdentifierBloomFilter> blooms = new HashMap<>();

        @Override
        public void addChangedListener(Runnable listener) {}

        @Override
        public void registerBloom(String resourceUri, IdentifierBloomFilter filter) {
            blooms.put(resourceUri, filter);
        }

        @Override
        public Map<String, IdentifierBloomFilter> bloomFilters() {
            return Map.copyOf(blooms);
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
        public boolean isEmpty() {
            return types.isEmpty() && modules.isEmpty() && blooms.isEmpty();
        }

        @Override
        public List<TypeEntry> getAll(String jvmName) {
            return types.stream().filter(t -> jvmName.equals(t.jvmOwnerName())).toList();
        }

        @Override
        public TypeEntry get(String jvmName) {
            return getAll(jvmName).stream().findFirst().orElse(null);
        }

        @Override
        public boolean contains(String jvmName) {
            return get(jvmName) != null;
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
