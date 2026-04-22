package ch.castleridge.javals.indexing.mbt;

import java.util.Collection;

/**
 * Top-level shape of an {@code mbt.json} file as emitted by the
 * {@code classpath-extractor} project.
 */
public class MbtInfo {
    public Collection<MbtTargetInfo> targets;
    public Collection<MbtDependencyModuleInfo> dependencyModules;
}
