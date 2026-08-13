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
package ch.castleridge.javals;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

public final class IndexTestUtils {
    private IndexTestUtils() {}

    public static TypeEntry get(Index index, String jvmName) {
        return index.getAll(jvmName).stream().findFirst().orElse(null);
    }
}
