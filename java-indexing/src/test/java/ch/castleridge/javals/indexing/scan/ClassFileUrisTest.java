package ch.castleridge.javals.indexing.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;

class ClassFileUrisTest {

    @Test
    void jvmOwnerNameFromJarEntryPath() {
        String jar = "file:///C:/lib/dep.jar";
        String resource = "jar:" + jar + "!/com/example/Hello.class";
        assertEquals("com/example/Hello", ClassFileUris.jvmOwnerName(resource, jar));
    }

    @Test
    void jvmOwnerNameFromJrtEntryPathStripsModulePrefix() {
        String jrtHome = "jrt:///C:/jdk";
        String resource = jrtHome + "!/modules/java.base/java/lang/Object.class";
        assertEquals("java/lang/Object", ClassFileUris.jvmOwnerName(resource, jrtHome));
    }

    @Test
    void jvmOwnerNameFromDirectoryRelativeToSourceRoot() {
        URI root = URI.create("file:///C:/proj/out/");
        URI clazz = URI.create("file:///C:/proj/out/com/foo/Bar.class");
        assertEquals("com/foo/Bar", ClassFileUris.jvmOwnerName(clazz, root));
    }

    @Test
    void jvmOwnerNameFromJrtEntryPathWithoutModulesPrefix() {
        String jrtHome = "jrt:///C:/jdk";
        String resource = jrtHome + "!/java.base/java/lang/Object.class";
        assertEquals("java/lang/Object", ClassFileUris.jvmOwnerName(resource, jrtHome));
    }

    @Test
    void nestedClassKeepsDollarInJvmName() {
        String jar = "file:///lib.jar";
        String resource = "jar:" + jar + "!/java/util/Base64$Encoder.class";
        assertEquals("java/util/Base64$Encoder", ClassFileUris.jvmOwnerName(resource, jar));
    }
}
