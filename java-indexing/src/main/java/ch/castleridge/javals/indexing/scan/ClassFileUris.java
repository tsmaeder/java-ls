package ch.castleridge.javals.indexing.scan;

import java.net.URI;
import java.nio.file.Path;

/**
 * Derives JVM binary names from resource URIs produced by the walkers,
 * without reading class file bytes.
 */
public final class ClassFileUris {

    private ClassFileUris() {}

    public static String jvmOwnerName(URI resourceUri, URI sourceUri) {
        return jvmOwnerName(resourceUri.toString(), sourceUri == null ? null : sourceUri.toString());
    }

    public static String jvmOwnerName(String resourceUri, String sourceUri) {
        String entryPath = entryPath(resourceUri, sourceUri);
        if (entryPath.endsWith(".class")) {
            entryPath = entryPath.substring(0, entryPath.length() - ".class".length());
        }
        return stripJrtModulePrefix(entryPath);
    }

    public static String simpleFileName(String resourceUri) {
        String tail = entryPath(resourceUri, null);
        int slash = tail.lastIndexOf('/');
        return slash < 0 ? tail : tail.substring(slash + 1);
    }

    private static String entryPath(String resourceUri, String sourceUri) {
        int bang = resourceUri.indexOf("!/");
        if (bang >= 0) {
            return resourceUri.substring(bang + 2);
        }
        if (sourceUri != null && resourceUri.startsWith("file:") && sourceUri.startsWith("file:")) {
            try {
                Path resource = Path.of(URI.create(resourceUri));
                Path source = Path.of(URI.create(sourceUri));
                if (resource.startsWith(source)) {
                    return source.relativize(resource).toString().replace('\\', '/');
                }
            } catch (RuntimeException ignored) {
            }
        }
        String path = URI.create(resourceUri).getPath();
        if (path == null) return "";
        if (path.startsWith("/")) path = path.substring(1);
        return path;
    }

    /**
     * Normalises jrt entry paths to a JVM binary name prefix.
     * <ul>
     *   <li>{@code modules/java.base/java/lang/Object} → {@code java/lang/Object}</li>
     *   <li>{@code java.base/java/lang/Object} → {@code java/lang/Object}</li>
     * </ul>
     */
    private static String stripJrtModulePrefix(String path) {
        path = path.replace('\\', '/');
        if (path.startsWith("modules/")) {
            int slash = path.indexOf('/', "modules/".length());
            return slash < 0 ? path : path.substring(slash + 1);
        }
        int slash = path.indexOf('/');
        if (slash > 0 && path.substring(0, slash).indexOf('.') >= 0) {
            return path.substring(slash + 1);
        }
        return path;
    }
}
