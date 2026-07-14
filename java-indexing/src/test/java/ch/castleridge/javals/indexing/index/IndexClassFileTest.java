package ch.castleridge.javals.indexing.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ch.castleridge.javals.indexing.model.ClassFileEntry;
import ch.castleridge.javals.indexing.model.ModuleFileEntry;

class IndexClassFileTest {

    @Test
    void classFileBucketsSupportDuplicatesAndMerge() {
        Index left = new Index();
        left.addClassFile(new ClassFileEntry("jar:///a!/Foo.class", "jar:///a", "com/Foo"));
        left.addModuleFile(new ModuleFileEntry("jar:///a!/module-info.class", "jar:///a", "mod.a", new String[] {"com"}));

        Index right = new Index();
        right.addClassFile(new ClassFileEntry("jar:///b!/Foo.class", "jar:///b", "com/Foo"));
        right.addClassFile(new ClassFileEntry("jar:///b!/Bar.class", "jar:///b", "com/Bar"));

        Index merged = new Index();
        merged.addAll(left);
        merged.addAll(right);

        assertEquals(2, merged.classFileSize());
        assertEquals(3, merged.allClassFiles().size());
        assertEquals(2, merged.getAllClassFiles("com/Foo").size());
        assertEquals(3, merged.listPackageClassFiles("com", false).size());
        assertEquals(0, merged.listPackageClassFiles("com", true).size());
        assertFalse(merged.isEmpty());
        assertEquals(1, merged.moduleFileCount());
        assertEquals("mod.a", merged.getModuleFile("mod.a").name());
    }

    @Test
    void entryCountIncludesClassFiles() {
        Index index = new Index();
        index.addClassFile(new ClassFileEntry("u1", "s1", "A"));
        index.addClassFile(new ClassFileEntry("u2", "s2", "A"));
        assertEquals(2, index.entryCount());
        assertTrue(index.containsClassFile("A"));
    }
}
