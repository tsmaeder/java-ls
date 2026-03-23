package ch.castleridge.javals.indexing.classfile;

import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.InMemoryIndexStore;
import ch.castleridge.javals.indexing.store.IndexStore;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Aggregates the declaration store for class files with a single {@link #indexClassFile} entry
 * point.
 */
public final class JavaClassIndex {

    private final DeclarationIndex declarations;
    private final ClassFileIndexer classFileIndexer;

    public JavaClassIndex(Executor storeExecutor) {
        IndexStore declStore = new InMemoryIndexStore(storeExecutor);
        this.declarations = new DeclarationIndex(declStore);
        this.classFileIndexer =
                new ClassFileIndexer(declarations, new AsmClassDeclarationExtractor());
    }

    /** For custom store wiring (e.g. JDBC backends). */
    public JavaClassIndex(DeclarationIndex declarations, ClassDeclarationExtractor declarationExtractor) {
        this.declarations = declarations;
        this.classFileIndexer = new ClassFileIndexer(declarations, declarationExtractor);
    }

    public CompletableFuture<Void> indexClassFile(URI classFileUri, byte[] classBytes) {
        return classFileIndexer.index(classFileUri, classBytes);
    }

    public DeclarationIndex declarations() {
        return declarations;
    }
}
