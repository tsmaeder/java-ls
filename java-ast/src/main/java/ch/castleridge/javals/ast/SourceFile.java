/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Original source buffer a compilation unit was lowered from. Trivia
 * (comments and whitespace) is not stored on the tree; query it here by
 * range when a rewrite needs it.
 */
public final class SourceFile {

    private final String uri;
    private final String text;

    public SourceFile(String uri, String text) {
        this.uri = uri == null ? "" : uri;
        this.text = text == null ? "" : text;
    }

    public String uri() {
        return uri;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    public String slice(int start, int end) {
        int lo = Math.max(0, start);
        int hi = Math.min(text.length(), Math.max(lo, end));
        return text.substring(lo, hi);
    }

    public String slice(SourceRange range) {
        if (range == null || !range.isPresent()) return "";
        return slice(range.start(), range.end());
    }

    public String slice(Node node) {
        return node == null ? "" : slice(node.range());
    }

    /**
     * Comments and whitespace in {@code [start, end)}. The slice is
     * tokenized on demand from the original buffer.
     */
    public List<Trivia> triviaIn(int start, int end) {
        int lo = Math.max(0, start);
        int hi = Math.min(text.length(), Math.max(lo, end));
        List<Trivia> out = new ArrayList<>();
        int i = lo;
        while (i < hi) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < hi) {
                char n = text.charAt(i + 1);
                if (n == '/') {
                    int from = i;
                    i += 2;
                    while (i < hi && text.charAt(i) != '\n') i++;
                    out.add(new Trivia(Trivia.Kind.LINE_COMMENT, from, i, text.substring(from, i)));
                    continue;
                }
                if (n == '*') {
                    int from = i;
                    boolean javadoc = i + 2 < hi && text.charAt(i + 2) == '*';
                    i += 2;
                    while (i + 1 < hi && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) i++;
                    i = Math.min(hi, i + 2);
                    out.add(new Trivia(javadoc ? Trivia.Kind.JAVADOC : Trivia.Kind.BLOCK_COMMENT,
                            from, i, text.substring(from, i)));
                    continue;
                }
            }
            if (Character.isWhitespace(c)) {
                int from = i;
                i++;
                while (i < hi && Character.isWhitespace(text.charAt(i))) i++;
                out.add(new Trivia(Trivia.Kind.WHITESPACE, from, i, text.substring(from, i)));
                continue;
            }
            i++;
        }
        return List.copyOf(out);
    }

    public List<Trivia> triviaIn(SourceRange range) {
        if (range == null || !range.isPresent()) return List.of();
        return triviaIn(range.start(), range.end());
    }

    /**
     * Leading javadoc immediately before {@code start}, skipping only
     * whitespace between the comment and that offset.
     */
    public String javadocBefore(int start) {
        int i = Math.min(text.length(), Math.max(0, start));
        int ws = i;
        while (ws > 0 && Character.isWhitespace(text.charAt(ws - 1))) ws--;
        if (ws < 2 || text.charAt(ws - 1) != '/' || text.charAt(ws - 2) != '*') return "";
        int from = ws - 2;
        while (from > 0 && !(text.charAt(from) == '/' && text.charAt(from + 1) == '*'
                && (from + 2 >= ws || text.charAt(from + 2) == '*'))) {
            from--;
        }
        if (from + 2 >= ws || text.charAt(from) != '/' || text.charAt(from + 1) != '*'
                || text.charAt(from + 2) != '*') {
            return "";
        }
        return text.substring(from, ws);
    }

    /**
     * Indentation (leading whitespace) of the line containing {@code offset}.
     */
    public String indentAt(int offset) {
        int i = Math.min(text.length(), Math.max(0, offset));
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        int end = i;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return text.substring(i, end);
    }

    /**
     * UTF-16 line and character of {@code offset}. Tabs count as one
     * character. {@code line} is 0-based.
     */
    public LineColumn lineColumn(int offset) {
        int bounded = Math.max(0, Math.min(text.length(), offset));
        int line = 0;
        int column = 0;
        for (int i = 0; i < bounded; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                column = 0;
            } else if (c != '\r') {
                column++;
            }
        }
        return new LineColumn(line, column);
    }

    /**
     * Offset of a 0-based line/character. Inverse of {@link #lineColumn(int)}.
     * Returns {@code -1} when the position is out of range.
     */
    public int offsetAt(int line, int character) {
        if (line < 0 || character < 0) return -1;
        int current = 0;
        int offset = 0;
        while (offset < text.length() && current < line) {
            if (text.charAt(offset++) == '\n') current++;
        }
        if (current != line) return -1;
        return Math.min(text.length(), offset + character);
    }

    public record LineColumn(int line, int character) {}
}
