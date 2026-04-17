package ch.castleridge.javals.javac;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * A {@link javax.tools.JavaFileManager} that layers a context-free
 * declaration {@link Index} on top of a standard file manager.
 *
 * <p>The index may contain multiple {@link TypeEntry} instances for the
 * same JVM binary name (e.g. when several jars on the build's classpath
 * declare the same type). A {@link ClasspathOrder} supplied to this
 * manager picks the winner deterministically: earlier entries in the
 * classpath shadow later ones, and entries whose source is not on the
 * classpath are ignored entirely.
 *
 * <p>For {@code CLASS} lookups on the classpath location the manager
 * synthesizes {@link IndexClassFileObject} instances for the winning
 * entries, then chains through to the wrapped
 * {@link StandardJavaFileManager} so real files on the classpath still
 * resolve when not shadowed by the index.
 */
@ClientCodeWrapper.Trusted
public class IndexFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Index index;
    private final ClasspathOrder classpath;

    public IndexFileManager(StandardJavaFileManager delegate, Index index) {
        this(delegate, index, ClasspathOrder.UNRESTRICTED);
    }

    public IndexFileManager(StandardJavaFileManager delegate, Index index, ClasspathOrder classpath) {
        super(delegate);
        this.index = Objects.requireNonNull(index);
        this.classpath = Objects.requireNonNull(classpath);
    }

    public Index index() {
        return index;
    }

    public ClasspathOrder classpath() {
        return classpath;
    }

    @Override
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<Kind> kinds,
                                         boolean recurse) throws IOException {
        Iterable<JavaFileObject> delegateListing = super.list(location, packageName, kinds, recurse);

        if (!kinds.contains(Kind.CLASS)) {
            return delegateListing;
        }
        if (!shouldAnswerFromIndex(location)) {
            return delegateListing;
        }

        String pkgJvm = packageName.replace('.', '/');
        Map<String, JavaFileObject> merged = new LinkedHashMap<>();
        for (JavaFileObject fo : delegateListing) {
            String binName = super.inferBinaryName(location, fo);
            merged.put(binName != null ? binName : fo.getName(), fo);
        }

        Map<String, TypeEntry> winners = new HashMap<>();
        Iterable<TypeEntry> candidates = recurse ? index.all() : index.listPackage(pkgJvm);
        String prefix = pkgJvm.isEmpty() ? "" : pkgJvm + "/";
        for (TypeEntry e : candidates) {
            if (!classpath.contains(e.sourceUri())) continue;
            String jvm = e.jvmOwnerName();
            if (recurse) {
                if (!prefix.isEmpty() && !jvm.startsWith(prefix)) continue;
            } else {
                if (!e.packageJvm().equals(pkgJvm)) continue;
            }
            String binName = jvm.replace('/', '.');
            TypeEntry existing = winners.get(binName);
            if (existing == null
                    || classpath.priorityOf(e.sourceUri()) < classpath.priorityOf(existing.sourceUri())) {
                winners.put(binName, e);
            }
        }
        for (Map.Entry<String, TypeEntry> w : winners.entrySet()) {
            merged.put(w.getKey(), new IndexClassFileObject(w.getValue()));
        }

        return new ArrayList<>(merged.values());
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return icfo.binaryName();
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean hasLocation(Location location) {
        if (shouldAnswerFromIndex(location) && hasAnyOnClasspath()) {
            return true;
        }
        return super.hasLocation(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        if (kind == Kind.CLASS && shouldAnswerFromIndex(location)) {
            String jvm = className.replace('.', '/');
            List<TypeEntry> all = index.getAll(jvm);
            if (!all.isEmpty()) {
                TypeEntry winner = classpath.pick(all);
                if (winner != null) {
                    return new IndexClassFileObject(winner);
                }
            }
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    private boolean hasAnyOnClasspath() {
        for (TypeEntry e : index.all()) {
            if (classpath.contains(e.sourceUri())) return true;
        }
        return false;
    }

    private static boolean shouldAnswerFromIndex(Location location) {
        if (location == StandardLocation.CLASS_PATH) return true;
        if (location == StandardLocation.SOURCE_PATH) return false;
        if (location == StandardLocation.SYSTEM_MODULES) return true;
        if (location == StandardLocation.MODULE_PATH) return true;
        return false;
    }

    /**
     * Returns true if the supplied file object is a synthetic
     * {@link IndexClassFileObject} known to this file manager.
     */
    public static boolean isIndexObject(JavaFileObject file) {
        return file instanceof IndexClassFileObject;
    }

    /** Convenience: cast and extract the backing entry, or null. */
    public static TypeEntry asEntry(JavaFileObject file) {
        return file instanceof IndexClassFileObject icfo ? icfo.entry() : null;
    }
}
