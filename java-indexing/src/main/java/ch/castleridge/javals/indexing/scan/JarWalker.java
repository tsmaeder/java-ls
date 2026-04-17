package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import ch.castleridge.javals.indexing.index.Index;

final class JarWalker {

    private JarWalker() {}

    static void walk(JarInput in, ResourceSink sink) {
        Path jar = in.jar();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String simple = simpleName(name);
                if (!isIndexable(simple)) continue;
                if (Index.isSkippedFileName(simple)) continue;

                URI uri = jarEntryUri(jar, name);
                JarEntry ref = e;
                sink.accept(uri, simple, () -> jf.getInputStream(ref).readAllBytes());
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed walking jar " + jar, ex);
        }
    }

    private static String simpleName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }

    private static URI jarEntryUri(Path jar, String entryName) {
        String jarUri = jar.toUri().toString();
        return URI.create("jar:" + jarUri + "!/" + entryName);
    }
}
