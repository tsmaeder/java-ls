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
package ch.castleridge.javals.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Reads the text of a Java source file addressed by an index resource URI.
 *
 * <p>Bytes go through {@link URLConnection}, so {@code file:} and
 * {@code jar:} source URIs work without special casing. Platform
 * {@code jdk:} class URIs are not read here; navigation uses attached
 * {@code src.zip} jar URIs instead.
 */
public final class SourceText {

    private SourceText() {}

    /** UTF-8 text at {@code uri}, or {@code null} when it cannot be read. */
    public static String read(String uri) {
        if (uri == null || uri.isBlank()) return null;
        try {
            URLConnection connection = URI.create(uri).toURL().openConnection();
            // The JDK's jar handler otherwise keeps every archive it read from
            // open for the lifetime of the process, which on Windows stops a
            // build from replacing a dependency jar while the server runs.
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException e) {
            return null;
        }
    }
}
