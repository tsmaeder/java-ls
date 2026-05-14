package ch.castleridge.javals.javac;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        this.index = index;
        this.classpath = classpath;
    }

    @Override
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<Kind> kinds,
                                         boolean recurse) throws IOException {

                                            
        if (!kinds.contains(Kind.CLASS)) {
            return super.list(location, packageName, kinds, recurse);
        }

        String pkgJvm = packageName.replace('.', '/');

        Map<String, TypeEntry> winners = new HashMap<>();
        Iterable<TypeEntry> candidates = index.listPackage(pkgJvm, recurse);
        for (TypeEntry e : candidates) {
            if (!classpath.contains(e.sourceUri())) continue;
            String jvm = e.jvmOwnerName();
            TypeEntry existing = winners.get(jvm);
            if (existing == null || classpath.pick(List.of(e, existing), TypeEntry::sourceUri) == e) {
                winners.put(jvm, e);
            }
        }
        return winners.values().stream().map(IndexClassFileObject::new).collect(Collectors.toList());
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof IndexClassFileObject icfo) {
            return icfo.binaryName();
        }
        return super.inferBinaryName(location, file);
    }


    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        if (kind == Kind.CLASS) {
            String jvm = className.replace('.', '/');
            List<TypeEntry> all = index.getAll(jvm);
            if (!all.isEmpty()) {
                TypeEntry winner = classpath.pick(all, TypeEntry::sourceUri);
                if (winner != null) {
                    return new IndexClassFileObject(winner);
                }
            }
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    /** Convenience: cast and extract the backing entry, or null. */
    public static TypeEntry asEntry(JavaFileObject file) {
        return file instanceof IndexClassFileObject icfo ? icfo.entry() : null;
    }
}
