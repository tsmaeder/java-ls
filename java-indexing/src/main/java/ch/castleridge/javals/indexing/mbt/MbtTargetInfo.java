package ch.castleridge.javals.indexing.mbt;

import java.util.List;

/** One target inside an {@code mbt.json} file (keyed by id in {@link MbtInfo#namespaces}). */
public class MbtTargetInfo {
    public List<String> compilerOptions;
    public String javaHome;
    public List<String> sources;
    public List<String> classes;
    public List<String> dependencyModules;
    public List<String> dependsOn;
}
