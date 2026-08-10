package ch.castleridge.javals.analysis.ecj;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * ECJ name environment backed exclusively by the declaration index.
 *
 * <p>Indexed declarations are exposed as {@link IBinaryType} adapters over
 * {@link TypeEntry}. Both source- and classfile-derived entries are treated
 * as binary types for binding construction (mirroring javac's
 * {@code IndexClassReader}).
 */
final class IndexNameEnvironment implements INameEnvironment {
    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<String, NameEnvironmentAnswer> answers = new ConcurrentHashMap<>();

    IndexNameEnvironment(Index index, ClasspathOrder classpath) {
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
    }

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundName) {
        if (compoundName == null || compoundName.length == 0) return null;
        StringBuilder name = new StringBuilder();
        for (char[] component : compoundName) {
            if (!name.isEmpty()) name.append('/');
            name.append(component);
        }
        return find(name.toString());
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        StringBuilder name = new StringBuilder();
        if (packageName != null) {
            for (char[] component : packageName) {
                if (!name.isEmpty()) name.append('/');
                name.append(component);
            }
        }
        if (!name.isEmpty()) name.append('/');
        name.append(typeName);
        return find(name.toString());
    }

    private NameEnvironmentAnswer find(String jvmName) {
        NameEnvironmentAnswer cached = answers.get(jvmName);
        if (cached != null) return cached;
        TypeEntry winner = classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
        if (winner == null) return null;
        try {
            IBinaryType binary = IndexBinaryType.of(winner, index, classpath);
            NameEnvironmentAnswer made = new NameEnvironmentAnswer(binary, null);
            NameEnvironmentAnswer prior = answers.putIfAbsent(jvmName, made);
            return prior == null ? made : prior;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        StringBuilder name = new StringBuilder();
        if (parentPackageName != null) {
            for (char[] component : parentPackageName) {
                if (!name.isEmpty()) name.append('/');
                name.append(component);
            }
        }
        if (!name.isEmpty()) name.append('/');
        name.append(packageName);
        return index.hasPackage(name.toString());
    }

    @Override
    public void cleanup() {
        answers.clear();
    }
}
