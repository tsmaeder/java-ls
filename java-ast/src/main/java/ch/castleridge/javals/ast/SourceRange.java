/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * Half-open UTF-16 range {@code [start, end)} into the compilation unit's
 * original source. Synthetic / factory-built nodes have {@link #NONE}.
 */
public record SourceRange(int start, int end) {

    public static final SourceRange NONE = new SourceRange(-1, -1);

    public SourceRange {
        if (start >= 0 && end < start) {
            throw new IllegalArgumentException("end < start: " + start + ".." + end);
        }
    }

    public boolean isPresent() {
        return start >= 0 && end >= start;
    }

    public int length() {
        return isPresent() ? end - start : 0;
    }
}
