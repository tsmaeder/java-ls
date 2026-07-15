package ch.castleridge.javals.indexing.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClassFileUrisTest {

    @Test
    void jvmOwnerNameFromRelativeJarPath() {
        assertEquals("com/example/Hello", ClassFileUris.jvmOwnerName("com/example/Hello.class"));
    }

    @Test
    void jvmOwnerNameFromJrtRelativePathStripsModulePrefix() {
        assertEquals("java/lang/Object",
                ClassFileUris.jvmOwnerName("modules/java.base/java/lang/Object.class"));
    }

    @Test
    void jvmOwnerNameFromDirectoryRelativePath() {
        assertEquals("com/foo/Bar", ClassFileUris.jvmOwnerName("com/foo/Bar.class"));
    }

    @Test
    void jvmOwnerNameFromJrtPathWithoutModulesPrefix() {
        assertEquals("java/lang/Object",
                ClassFileUris.jvmOwnerName("java.base/java/lang/Object.class"));
    }

    @Test
    void nestedClassKeepsDollarInJvmName() {
        assertEquals("java/util/Base64$Encoder",
                ClassFileUris.jvmOwnerName("java/util/Base64$Encoder.class"));
    }

    @Test
    void jvmOwnerNameFromFullJarUriStillWorks() {
        String jar = "file:///C:/lib/dep.jar";
        String resource = "jar:" + jar + "!/com/example/Hello.class";
        assertEquals("com/example/Hello", ClassFileUris.jvmOwnerName(resource, jar));
    }
}
