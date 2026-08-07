package ch.castleridge.javals.analysis;

import java.net.URI;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

/**
 * Analyzes a single Java source against the declaration {@link Index}
 * under a {@link ClasspathOrder} visibility view.
 */
public interface WorkspaceCompiler {

    AnalysisSession analyze(URI uri, CharSequence text, Index index, ClasspathOrder classpath);
}
