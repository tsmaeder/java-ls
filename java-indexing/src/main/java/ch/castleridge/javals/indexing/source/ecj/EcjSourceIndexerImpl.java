/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.ecj;

import ch.castleridge.javals.indexing.index.Index;

/**
 * Package-private entry used by {@link EcjSourceIndexer}.
 * Implementation lives in {@link EcjSourceIndexerEngine}.
 */
final class EcjSourceIndexerImpl {
    private EcjSourceIndexerImpl() {}

    static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        EcjSourceIndexerEngine.index(resourcePath, sourceUri, content, into);
    }
}
