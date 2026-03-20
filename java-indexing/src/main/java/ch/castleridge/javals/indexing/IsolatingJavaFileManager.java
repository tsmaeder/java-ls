package ch.castleridge.javals.indexing;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Hides every {@link JavaFileManager} location that could supply class or source definitions from
 * outside the explicit compilation units passed to {@link javax.tools.JavaCompiler#getTask}.
 * <p>
 * Implicit {@code java.lang} usage is preserved: package {@code java.lang} listings and class
 * resolution for {@code java.lang.*} types delegate to the standard file manager. Other JDK
 * packages stay hidden so classpath/module isolation still applies to {@code java.util} and the
 * like. {@link StandardLocation#SYSTEM_MODULES} is listed like the delegate (otherwise modular
 * javac aborts early); {@link #getJavaFileForInput} and {@link #getClassLoader} enforce the
 * {@code java.lang} boundary.
 */
final class IsolatingJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    IsolatingJavaFileManager(StandardJavaFileManager delegate) {
        super(delegate);
    }

    @Override
    public Iterable<Set<JavaFileManager.Location>> listLocationsForModules(JavaFileManager.Location location)
            throws IOException {
        if (isExternalModuleLocation(location)) {
            return Collections.emptyList();
        }
        return super.listLocationsForModules(location);
    }

    private static boolean isExternalModuleLocation(JavaFileManager.Location location) {
        return StandardLocation.MODULE_PATH.equals(location)
                || StandardLocation.UPGRADE_MODULE_PATH.equals(location)
                || StandardLocation.PATCH_MODULE_PATH.equals(location)
                || StandardLocation.ANNOTATION_PROCESSOR_MODULE_PATH.equals(location);
    }

    @Override
    public ClassLoader getClassLoader(JavaFileManager.Location location) {
        ClassLoader base = super.getClassLoader(location);
        if (base == null || !wrapClassLoaderForLocation(location)) {
            return base;
        }
        return new JavaLangOnlyClassLoader(base);
    }

    /** Locations whose class loaders may load the JDK platform; wrap to keep only {@code java.lang}. */
    private static boolean wrapClassLoaderForLocation(JavaFileManager.Location location) {
        return StandardLocation.PLATFORM_CLASS_PATH.equals(location)
                || StandardLocation.CLASS_PATH.equals(location)
                || StandardLocation.MODULE_PATH.equals(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(
            JavaFileManager.Location location, String className, JavaFileObject.Kind kind) throws IOException {
        if (shouldHideNonJavaLangBinary(className)) {
            throw new FileNotFoundException(className);
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public Iterable<JavaFileObject> list(
            JavaFileManager.Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse)
            throws IOException {
        if (!isJavaLangPackage(packageName)
                && !location.isOutputLocation()
                && hideNonJavaLangPackagesFromList(location)) {
            return Collections.emptyList();
        }
        return super.list(location, packageName, kinds, recurse);
    }

    /**
     * True for classpath/platform locations and for module-specific roots that belong to the JDK
     * (e.g. {@code java.base}), so {@code java.util} is not discoverable while {@code java.lang} is.
     */
    private boolean hideNonJavaLangPackagesFromList(JavaFileManager.Location location) {
        if (isExternalListingLocation(location)) {
            return true;
        }
        try {
            String moduleName = inferModuleName(location);
            return moduleName != null && isJdkRuntimeModuleName(moduleName);
        } catch (IllegalArgumentException | IOException ignored) {
            return false;
        }
    }

    private static boolean isJdkRuntimeModuleName(String moduleName) {
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.");
    }

    /** True for {@code java.lang} and subpackages ({@code java.lang.annotation}, etc.). */
    private static boolean isJavaLangPackage(String packageName) {
        return "java.lang".equals(packageName) || packageName.startsWith("java.lang.");
    }

    private static boolean isExternalListingLocation(JavaFileManager.Location location) {
        return StandardLocation.CLASS_PATH.equals(location)
                || StandardLocation.SOURCE_PATH.equals(location)
                || StandardLocation.PLATFORM_CLASS_PATH.equals(location)
                || StandardLocation.MODULE_SOURCE_PATH.equals(location)
                || StandardLocation.ANNOTATION_PROCESSOR_PATH.equals(location);
    }

    /**
     * Blocks {@code java.*} types outside the {@code java.lang} tree (and all {@code javax.*}).
     * Leaves non-{@code java}/{@code javax} names alone so user code and source paths keep working.
     */
    private static boolean shouldHideNonJavaLangBinary(String binaryName) {
        if (binaryName.startsWith("javax.")) {
            return true;
        }
        return binaryName.startsWith("java.") && !binaryName.startsWith("java.lang.");
    }

    private static final class JavaLangOnlyClassLoader extends ClassLoader {
        JavaLangOnlyClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) {
                    return c;
                }
                if (shouldHideNonJavaLangBinary(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
