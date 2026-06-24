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

    static void walk(JarInput in, ResourceSink sink, boolean catalogClassFilesOnly) {
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

                String uri = jarEntryUri(jar, name);
                if (catalogClassOnly(catalogClassFilesOnly, simple)) {
                    sink.accept(uri, simple, null);
                    continue;
                }
                // Read bytes eagerly: the sink typically hands the bytes supplier
                // to an async task, and by the time the task runs the
                // try-with-resources below would have closed the JarFile.
                byte[] bytes;
                try {
                    bytes = jf.getInputStream(e).readAllBytes();
                } catch (IOException ioe) {
                    System.err.println("Skipping unreadable jar entry " + jar + "!/" + name
                            + ": " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                    continue;
                }
                sink.accept(uri, simple, () -> bytes);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("Skipping non-readable jar " + jar + ": "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static boolean catalogClassOnly(boolean catalog, String simple) {
        return catalog && simple.endsWith(".class") && !Index.isModuleInfoFileName(simple);
    }

    private static String simpleName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static boolean isIndexable(String name) {
        return name.endsWith(".java") || name.endsWith(".class");
    }

    private static String jarEntryUri(Path jar, String entryName) {
        String jarUri = jar.toUri().toString();
        return "jar:" + jarUri + "!/" + entryName;
    }
}
