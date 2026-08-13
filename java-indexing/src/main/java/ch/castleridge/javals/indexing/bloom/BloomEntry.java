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
package ch.castleridge.javals.indexing.bloom;

import ch.castleridge.javals.indexing.model.ResourceUris;

/**
 * One per-resource identifier bloom filter, addressed the same way as
 * type entries: classpath {@code sourceUri} plus compact
 * {@code resourcePath}.
 */
public record BloomEntry(String sourceUri, String resourcePath, IdentifierBloomFilter filter) {
    public String resourceUri() {
        return ResourceUris.resolve(sourceUri, resourcePath);
    }
}
