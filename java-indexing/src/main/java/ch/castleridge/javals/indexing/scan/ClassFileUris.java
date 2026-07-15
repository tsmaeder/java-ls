package ch.castleridge.javals.indexing.scan;

import java.net.URI;

import ch.castleridge.javals.indexing.model.ResourceUris;

/**
 * Derives JVM binary names from relative entry paths (or full resource URIs)
 * without reading class file bytes.
 */
public final class ClassFileUris {

    private ClassFileUris() {}

    /**
     * Derive the JVM owner name from a relative entry path such as
     * {@code com/example/Hello.class} or
     * {@code modules/java.base/java/lang/Object.class}.
     */
    public static String jvmOwnerName(String relativePath) {
        String entryPath = normalizeEntryPath(relativePath);
        if (entryPath.endsWith(".class")) {
            entryPath = entryPath.substring(0, entryPath.length() - ".class".length());
        }
        return stripJrtModulePrefix(entryPath);
    }

    /** Overload accepting {@link URI} resource and source locations. */
    public static String jvmOwnerName(URI resourceUri, URI sourceUri) {
        return jvmOwnerName(resourceUri.toString(), sourceUri == null ? null : sourceUri.toString());
    }

    /**
     * Accepts either a relative path or a full resource URI. When
     * {@code sourceUri} is provided, absolute URIs are compacted to a
     * relative path first.
     */
    public static String jvmOwnerName(String resourceUriOrPath, String sourceUri) {
        String entryPath = entryPath(resourceUriOrPath, sourceUri);
        if (entryPath.endsWith(".class")) {
            entryPath = entryPath.substring(0, entryPath.length() - ".class".length());
        }
        return stripJrtModulePrefix(entryPath);
    }

    public static String simpleFileName(String resourceUriOrPath) {
        String tail = entryPath(resourceUriOrPath, null);
        int slash = tail.lastIndexOf('/');
        return slash < 0 ? tail : tail.substring(slash + 1);
    }

    private static String entryPath(String resourceUriOrPath, String sourceUri) {
        if (resourceUriOrPath == null || resourceUriOrPath.isEmpty()) return "";
        String relative = ResourceUris.relativePath(resourceUriOrPath, sourceUri);
        if (relative != null) return relative;
        return normalizeEntryPath(resourceUriOrPath);
    }

    private static String normalizeEntryPath(String path) {
        if (path == null || path.isEmpty()) return "";
        // Opaque absolute URI with a path component — peel that off.
        if (path.indexOf("://") >= 0 || path.startsWith("jar:")) {
            try {
                String uriPath = URI.create(path).getPath();
                if (uriPath != null) {
                    if (uriPath.startsWith("/")) uriPath = uriPath.substring(1);
                    return uriPath;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (path.startsWith("/")) return path.substring(1);
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
