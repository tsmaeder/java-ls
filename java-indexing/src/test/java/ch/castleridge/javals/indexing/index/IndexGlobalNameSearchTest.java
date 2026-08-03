package ch.castleridge.javals.indexing.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;

class IndexGlobalNameSearchTest {

    @Test
    void searchTypesBySimpleNamePrefixMatchesAcrossPackages() {
        Index index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo"));
        index.add(sourceType("com/other/FooBar"));
        index.add(sourceType("com/example/Baz"));

        List<TypeEntry> matches = index.searchTypesBySimpleNamePrefix("Foo", 0);
        Set<String> names = matches.stream().map(TypeEntry::jvmOwnerName).collect(Collectors.toSet());
        assertEquals(Set.of("com/example/Foo", "com/other/FooBar"), names);
    }

    @Test
    void searchTypesBySimpleNamePrefixExcludesNestedTypes() {
        Index index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo"));
        index.add(sourceType("com/example/Foo$Inner"));

        List<TypeEntry> matches = index.searchTypesBySimpleNamePrefix("Foo", 0);
        assertEquals(1, matches.size());
        assertEquals("com/example/Foo", matches.get(0).jvmOwnerName());
    }

    @Test
    void searchTypesBySimpleNamePrefixRespectsLimit() {
        Index index = new InMemoryIndex();
        for (int i = 0; i < 10; i++) {
            index.add(sourceType("com/example/Foo" + i));
        }

        List<TypeEntry> matches = index.searchTypesBySimpleNamePrefix("Foo", 3);
        assertEquals(3, matches.size());
    }

    @Test
    void searchWithNoMatchesReturnsEmpty() {
        Index index = new InMemoryIndex();
        index.add(sourceType("com/example/Foo"));

        assertTrue(index.searchTypesBySimpleNamePrefix("Zzz", 0).isEmpty());
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
}
