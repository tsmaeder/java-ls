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
package ch.castleridge.javals.indexing.index;

import java.net.URI;

public final class InMemorySource extends AbstractJavaFileObject {
    private final CharSequence text;

    public InMemorySource(URI uri, CharSequence text) {
        super(uri, Kind.SOURCE);
        this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return text;
    }
}
