package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.classpath.ClasspathOrder;

import com.sun.tools.javac.code.Symbol.ClassSymbol;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.EmptyArrays;
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
            return;
        }
        symbol.classfile = new IndexClassFileObject(missingTypeEntry(jvmName));
    }

    static TypeEntry missingTypeEntry(String jvmName) {
        return new ClassFileTypeEntry(
                "index:///missing/" + jvmName,
                null,
                jvmName,
                0,
                null,
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.FIELD,
                EmptyArrays.METHOD,
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF);
    }
}
