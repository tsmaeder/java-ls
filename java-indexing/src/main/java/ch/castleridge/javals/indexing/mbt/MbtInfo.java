package ch.castleridge.javals.indexing.mbt;

import java.util.Collection;
import java.util.Map;

/**
 * Top-level shape of an {@code mbt.json} file as emitted by the
 * {@code classpath-extractor} project.
 */
public class MbtInfo {
    public Map<String, MbtTargetInfo> namespaces;
    public Collection<MbtDependencyModuleInfo> dependencyModules;
}
