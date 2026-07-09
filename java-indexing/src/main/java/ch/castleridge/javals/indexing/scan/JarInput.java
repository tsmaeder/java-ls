package ch.castleridge.javals.indexing.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import ch.castleridge.javals.indexing.index.Index;

/** A single {@code .jar} file. */
public record JarInput(Path jar) implements InputSource {
    @Override
    public void walk(ResourceSink sink, boolean indexClassFiles) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String simple = simpleName(name);
                if (isIndexable(simple)) {
                    String uri = jarEntryUri(jar, name);
                    if (shouldReadContents(indexClassFiles, simple)) {
                        // Read bytes eagerly: the sink typically hands the bytes supplier
                        // to an async task, and by the time the task runs the
                        // try-with-resources below would have closed the JarFile.
                        try {
                            byte[] bytes = jf.getInputStream(e).readAllBytes();
                            sink.accept(uri, simple, () -> bytes);
                        } catch (IOException ioe) {
                            System.err.println("Skipping unreadable jar entry " + jar + "!/" + name
                                    + ": " + ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                        }
                    } else {
                        sink.accept(uri, simple, null);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("Skipping non-readable jar " + jar + ": "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Override
    public String sourceUri() {
        // Use the underlying file URI (not the jar: wrapper): the resource
        // URIs emitted for entries inside the jar start with
        // jar:<file-uri>!/..., so the file URI is the cleanest prefix-free
        // key for a jar as a classpath entry.
        return jar.toUri().toString();
    }

    private static boolean shouldReadContents(boolean indexClassFiles, String name) {
        return indexClassFiles || !name.endsWith(".class") || Index.isModuleInfoFileName(name);
    }

    private static String simpleName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    private static boolean isIndexable(String name) {
        return (name.endsWith(".java") || name.endsWith(".class")) && !Index.isSkippedFileName(name);
    }

    private static String jarEntryUri(Path jar, String entryName) {
        String jarUri = jar.toUri().toString();
        return "jar:" + jarUri + "!/" + entryName;
    }
}
