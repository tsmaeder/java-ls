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

import ch.castleridge.javals.indexing.index.UriCoding;

/**
 * File-identity comparison for symbol origins. The comparison ignores
 * percent-encoding and Windows drive-letter case so an editor URI and the
 * index {@code resourceUri} for the same path compare equal. Callers that
 * embed an origin in a {@link ch.castleridge.javals.ast.SymbolKey} still store
 * the index spelling unchanged.
 */
public final class FileUris {

    private FileUris() {}

    /**
     * True when {@code a} and {@code b} name the same file. Drive-letter case
     * is folded explicitly so the check does not depend on {@code Path.equals},
     * which is case-sensitive off Windows.
     */
    public static boolean sameFile(String a, String b) {
        if (a == null || b == null) return false;
        return canonical(a).equals(canonical(b));
    }

    static String canonical(String uri) {
        return lowercaseDrive(UriCoding.decode(uri));
    }

    private static String lowercaseDrive(String uri) {
        int file = uri.indexOf("file:");
        if (file < 0) return uri;
        int i = file + "file:".length();
        while (i < uri.length() && uri.charAt(i) == '/') i++;
        if (i + 1 < uri.length()
                && uri.charAt(i + 1) == ':'
                && uri.charAt(i) >= 'A' && uri.charAt(i) <= 'Z') {
            return uri.substring(0, i) + (char) (uri.charAt(i) - 'A' + 'a') + uri.substring(i + 1);
        }
        return uri;
    }
}
