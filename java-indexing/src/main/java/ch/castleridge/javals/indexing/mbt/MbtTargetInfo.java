package ch.castleridge.javals.indexing.mbt;

import java.util.List;

/** One target inside an {@code mbt.json} file. */
public class MbtTargetInfo {
    public String id;
    public List<String> javacOptions;
    public String jdk;
    public List<String> sources;
    public List<String> classes;
    public List<String> dependencyModules;
}
