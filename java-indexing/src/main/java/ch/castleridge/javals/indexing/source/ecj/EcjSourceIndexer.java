package ch.castleridge.javals.indexing.source.ecj;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.source.SourceIndexer;

/**
 * ECJ-based source indexer. Parses without classpath resolution and emits
 * the same {@link ch.castleridge.javals.indexing.model.SourceTypeEntry} model
 * as the javac indexer.
 */
public final class EcjSourceIndexer {

    public static final SourceIndexer INSTANCE = EcjSourceIndexer::index;

    private EcjSourceIndexer() {}

    public static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        EcjSourceIndexerImpl.index(resourcePath, sourceUri, content, into);
    }
}
