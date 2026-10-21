/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.nio.charset.StandardCharsets;

import ch.castleridge.javals.indexing.FileIndexer;
import ch.castleridge.javals.indexing.FileIndexerRegistry;
import ch.castleridge.javals.indexing.index.Index;

/**
 * Parse-only indexer for {@code .kt} sources. Emits {@code SourceTypeEntry}
 * declarations and identifier bloom filters; does not resolve against a
 * classpath.
 */
public final class KotlinSourceIndexer implements FileIndexer {

    public static final KotlinSourceIndexer INSTANCE = new KotlinSourceIndexer();

    private KotlinSourceIndexer() {}

    @Override
    public String extension() {
        return FileIndexerRegistry.EXT_KT;
    }

    @Override
    public void index(String resourcePath, String sourceUri, byte[] content, Index into) {
        if (content == null || into == null) return;
        index(resourcePath, sourceUri, new String(content, StandardCharsets.UTF_8), into);
    }

    public static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        KotlinSourceIndexerImpl.index(resourcePath, sourceUri, content, into);
    }
}
