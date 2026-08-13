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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class UriCoding {
    public static String decode(String uri) {
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        while (!uri.equals(decoded)) {
            uri = decoded;
            decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        }
        return decoded;
    }
}