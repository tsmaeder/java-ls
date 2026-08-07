package ch.castleridge.javals.analysis;

import java.util.Map;

import ch.castleridge.javals.analysis.ecj.EcjWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.JavacWorkspaceCompiler;
import ch.castleridge.javals.analysis.javac.SourceCache;
import ch.castleridge.javals.analysis.javac.SymbolLocator;

/**
 * Selects indexer/compiler implementations from configuration names.
 */
public final class BackendFactory {

    private BackendFactory() {}

    public static WorkspaceCompiler workspaceCompiler(String name) {
        return workspaceCompiler(name, new SymbolLocator(new SourceCache()), Map.of());
    }

    public static WorkspaceCompiler workspaceCompiler(String name,
                                                      SymbolLocator symbolLocator,
                                                      Map<String, String> sourceJarByBinaryJar) {
        if (name != null && name.trim().equalsIgnoreCase("ecj")) {
            return new EcjWorkspaceCompiler(sourceJarByBinaryJar);
        }
        return new JavacWorkspaceCompiler(symbolLocator, sourceJarByBinaryJar);
    }
}
