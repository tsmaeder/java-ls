/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * A comment or whitespace run in the original source. Not stored on the
 * tree; produced by {@link SourceFile#triviaIn(int, int)}.
 */
public record Trivia(Kind kind, int start, int end, String text) {

    public enum Kind {
        WHITESPACE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        JAVADOC
    }

    public boolean isComment() {
        return kind != Kind.WHITESPACE;
    }

    public boolean isJavadoc() {
        return kind == Kind.JAVADOC;
    }
}
