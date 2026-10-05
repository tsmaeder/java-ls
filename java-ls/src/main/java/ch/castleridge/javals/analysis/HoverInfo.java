/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

/**
 * Hover payload: declaration signature plus optional raw javadoc comment text.
 */
public record HoverInfo(String signature, String javadoc) {

    public HoverInfo {
        signature = signature == null ? "" : signature;
        javadoc = javadoc == null ? "" : javadoc;
    }

    public boolean hasJavadoc() {
        return !javadoc.isBlank();
    }
}
