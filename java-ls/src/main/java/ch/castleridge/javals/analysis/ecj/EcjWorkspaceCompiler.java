package ch.castleridge.javals.analysis.ecj;

import java.net.URI;

import ch.castleridge.javals.analysis.AnalysisSession;
import ch.castleridge.javals.analysis.WorkspaceCompiler;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * ECJ-backed workspace compiler using {@link IndexNameEnvironment} instead
 * of javac's IndexFileManager / IndexClassReader.
 */
public final class EcjWorkspaceCompiler implements WorkspaceCompiler {

    public EcjWorkspaceCompiler() {
    }

    @Override
    public AnalysisSession analyze(URI uri, CharSequence text, Index index, ClasspathOrder classpath) {
        return EcjAnalysisSession.compile(uri, text, index, classpath);
    }
}
