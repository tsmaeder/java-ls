package ch.castleridge.javals.javac;

import java.util.List;

import com.sun.tools.javac.code.Symbol.ClassSymbol;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Ensures every {@link ClassSymbol} has a {@code classfile} before javac's
 * {@code ClassFinder.fillIn} runs. Stock {@code fillIn} throws
 * {@code CompletionFailure} when {@code classfile == null}, which aborts
 * attribution without assigning {@code JCFieldAccess.sym}.
 */
final class IndexClassfileAttachment {

    private IndexClassfileAttachment() {}

    static void attachIfMissing(ClassSymbol symbol, Index index, ClasspathOrder classpath) {
        if (symbol.classfile != null) {
            return;
        }
        String jvmName = symbol.flatName().toString().replace('.', '/');
        TypeEntry refEntry = classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
        if (refEntry != null) {
            symbol.classfile = new IndexClassFileObject(refEntry);
        } else {
            symbol.classfile = new IndexClassFileObject(missingTypeEntry(jvmName));
        }
    }

    static TypeEntry missingTypeEntry(String jvmName) {
        return new TypeEntry(
                "index:///missing/" + jvmName,
                null,
                jvmName,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }
}
