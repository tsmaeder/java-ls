/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals;

/**
 * Which indexed containers find-references may scan beyond workspace sources.
 *
 * <p>Workspace {@code .java} files are always eligible. Dependency jars and
 * the JDK are included only when the matching flag is set. Open documents
 * are added by the caller and are not filtered here.
 */
record ReferenceSearchScope(boolean inJars, boolean inJdk) {

    static final ReferenceSearchScope DEFAULT = new ReferenceSearchScope(false, false);

    /**
     * @param sourceUri classpath container stamped on the bloom entry
     *        (a source root, a jar, or {@code jdk:///<home>!/})
     * @param resourcePath entry path ({@code com/example/Foo.java}
     *        or {@code java.base/java/lang/Object.class})
     */
    boolean include(String sourceUri, String resourcePath) {
        if (resourcePath == null) return false;
        if (!resourcePath.endsWith(".java") && !resourcePath.endsWith(".class")) return false;
        return includeContainer(sourceUri);
    }

    private boolean includeContainer(String sourceUri) {
        if (sourceUri == null || sourceUri.isEmpty()) {
            return true;
        }
        if (sourceUri.startsWith("jdk:")) {
            return inJdk;
        }
        if (sourceUri.startsWith("jar:")) {
            return inJars;
        }
        return true;
    }
}
