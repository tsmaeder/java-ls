/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing;

import ch.castleridge.javals.indexing.index.Index;

/**
 * Indexes a single file of a given extension into an {@link Index}.
 *
 * <p>Implementations must be classpath-free (parse-only for sources).
 */
public interface FileIndexer {

    /**
     * File extension this indexer handles, including the leading dot and
     * lower-cased (e.g. {@code ".java"}, {@code ".kt"}, {@code ".class"}).
     */
    String extension();

    /**
     * Indexes {@code content} for {@code resourcePath} under {@code sourceUri}
     * into {@code into}.
     */
    void index(String resourcePath, String sourceUri, byte[] content, Index into);
}
