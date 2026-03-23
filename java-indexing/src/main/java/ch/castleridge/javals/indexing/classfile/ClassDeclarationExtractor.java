package ch.castleridge.javals.indexing.classfile;

import ch.castleridge.javals.indexing.store.IndexEntry;

import java.net.URI;
import java.util.List;

/** Extracts declaration rows from a class file. */
public interface ClassDeclarationExtractor {

    List<IndexEntry> extract(URI resourceUri, byte[] classBytes);
}
