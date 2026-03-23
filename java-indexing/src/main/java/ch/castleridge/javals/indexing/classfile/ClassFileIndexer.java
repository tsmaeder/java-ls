package ch.castleridge.javals.indexing.classfile;

import ch.castleridge.javals.indexing.declaration.DeclarationIndex;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Indexes one class file: remove prior rows for the URI, then insert declarations. */
public final class ClassFileIndexer {

    private final DeclarationIndex declarations;
    private final ClassDeclarationExtractor declarationExtractor;

    public ClassFileIndexer(
            DeclarationIndex declarations, ClassDeclarationExtractor declarationExtractor) {
        this.declarations = declarations;
        this.declarationExtractor = declarationExtractor;
    }

    /**
     * Removes existing index entries for {@code classFileUri}, then inserts fresh declaration rows.
     * Work after the first step may run on the completing thread of the store futures (see {@link
     * Executor} passed to {@link ch.castleridge.javals.indexing.store.InMemoryIndexStore}).
     */
    public CompletableFuture<Void> index(URI classFileUri, byte[] classBytes) {
        return declarations
                .removeForResource(classFileUri)
                .thenCompose(ignored -> declarations.insertAll(declarationExtractor.extract(classFileUri, classBytes)));
    }
}
