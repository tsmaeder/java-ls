/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Original source buffer a compilation unit was lowered from. Trivia
 * (comments and whitespace) is not stored on the tree; query it here by
 * range when a rewrite needs it.
 */
public final class SourceFile {

    /** Line number sampled every {@code 1 << STRIDE_SHIFT} characters. */
    private static final int STRIDE_SHIFT = 8;
    private static final int STRIDE = 1 << STRIDE_SHIFT;

    private final String uri;
    private final String text;
    private volatile LineIndex lineIndex;

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
     * Leading javadoc for a declaration whose range starts at {@code start}.
     * Skips whitespace and a simple declaration header (modifiers, type,
     * annotations, type parameters) so diet-parse ranges that begin at the
     * declared name still find the comment.
     */
    public String javadocBefore(int start) {
        int i = Math.min(text.length(), Math.max(0, start));
        int at = skipDeclarationHeaderLeft(i);
        if (at < 2 || text.charAt(at - 1) != '/' || text.charAt(at - 2) != '*') return "";
        int from = at - 2;
        while (from > 0 && !(text.charAt(from) == '/' && text.charAt(from + 1) == '*'
                && (from + 2 >= at || text.charAt(from + 2) == '*'))) {
            from--;
        }
        if (from + 2 >= at || text.charAt(from) != '/' || text.charAt(from + 1) != '*'
                || text.charAt(from + 2) != '*') {
            return "";
        }
        return text.substring(from, at);
    }

    /**
     * Walk left from {@code start} through whitespace and declaration-header
     * tokens until the end of a preceding block comment or a hard stop such as
     * a semicolon or closing brace.
     */
    private int skipDeclarationHeaderLeft(int start) {
        int p = start;
        while (p > 0 && Character.isWhitespace(text.charAt(p - 1))) p--;
        while (p > 0) {
            char c = text.charAt(p - 1);
            if (Character.isWhitespace(c) || Character.isJavaIdentifierPart(c)
                    || c == '.' || c == '[' || c == ']' || c == '<' || c == '>'
                    || c == ',' || c == '@' || c == '?' || c == '&'
                    || c == '(' || c == ')') {
                p--;
                continue;
            }
            if (c == '*') {
                // Generics star, or the '*' of a closing '*/'
                if (p >= 2 && text.charAt(p - 2) == '/') {
                    return p; // pointing after '*/'
                }
                p--;
                continue;
            }
            break;
        }
        return p;
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
     * character. {@code line} is 0-based. A line break is {@code \n};
     * {@code \r} does not advance the column when it belongs to a CRLF pair.
     */
    public LineColumn lineColumn(int offset) {
        LineIndex idx = index();
        int bounded = bound(offset);
        int line = lineAt(idx, bounded);
        return new LineColumn(line, columnOf(idx, line, bounded));
    }

    /**
     * Remembers the last offset so a walk in source order converts positions
     * by stepping forward. A backward jump, or a jump of more than one
     * stride, uses the stride index. Each call is O(1).
     */
    public Cursor cursor() {
        return new Cursor();
    }

    /**
     * Offset of a 0-based line/character. Inverse of {@link #lineColumn(int)}
     * for LF text. Returns {@code -1} when the line is out of range.
     * {@code character} is added to the line start and clamped to the buffer;
     * it does not skip {@code \r}.
     */
    public int offsetAt(int line, int character) {
        if (line < 0 || character < 0) return -1;
        int[] starts = index().lineStarts;
        if (line >= starts.length) return -1;
        return Math.min(text.length(), starts[line] + character);
    }

    public record LineColumn(int line, int character) {}

    public final class Cursor {
        private int line;
        private int offset;

        public LineColumn lineColumn(int target) {
            LineIndex idx = index();
            int bounded = bound(target);
            if (bounded < offset || bounded - offset > STRIDE) {
                line = lineAt(idx, bounded);
            } else {
                int[] starts = idx.lineStarts;
                while (line + 1 < starts.length && starts[line + 1] <= bounded) {
                    line++;
                }
            }
            offset = bounded;
            return new LineColumn(line, columnOf(idx, line, bounded));
        }
    }

    private int bound(int offset) {
        return Math.max(0, Math.min(text.length(), offset));
    }

    private LineIndex index() {
        LineIndex idx = lineIndex;
        if (idx != null) return idx;
        idx = LineIndex.build(text);
        lineIndex = idx;
        return idx;
    }

    /** 0-based line of {@code offset}, counting {@code \n} in at most one stride. */
    private int lineAt(LineIndex idx, int offset) {
        int line = idx.strideLines[offset >>> STRIDE_SHIFT];
        int i = (offset >>> STRIDE_SHIFT) << STRIDE_SHIFT;
        for (; i < offset; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Column is the distance from the line start. The {@code \n} of a CRLF
     * pair sits one column earlier because {@code \r} does not advance it.
     */
    private int columnOf(LineIndex idx, int line, int offset) {
        int start = idx.lineStarts[line];
        int column = offset - start;
        if (offset > start && offset < text.length()
                && text.charAt(offset) == '\n'
                && text.charAt(offset - 1) == '\r') {
            column--;
        }
        return column;
    }

    private static final class LineIndex {
        final int[] lineStarts;
        final int[] strideLines;

        private LineIndex(int[] lineStarts, int[] strideLines) {
            this.lineStarts = lineStarts;
            this.strideLines = strideLines;
        }

        static LineIndex build(String text) {
            int length = text.length();
            int buckets = (length >>> STRIDE_SHIFT) + 1;
            int[] strideLines = new int[buckets];
            int[] starts = new int[Math.max(1, Math.min(length + 1, (length >>> 5) + 1))];
            starts[0] = 0;
            int lines = 1;
            int line = 0;
            int strideAt = STRIDE;
            int strideIndex = 1;
            for (int i = 0; i < length; i++) {
                if (text.charAt(i) == '\n') {
                    if (lines == starts.length) {
                        starts = Arrays.copyOf(starts, starts.length << 1);
                    }
                    starts[lines++] = i + 1;
                    line++;
                }
                if (i + 1 == strideAt && strideIndex < buckets) {
                    strideLines[strideIndex++] = line;
                    strideAt += STRIDE;
                }
            }
            if (lines != starts.length) {
                starts = Arrays.copyOf(starts, lines);
            }
            return new LineIndex(starts, strideLines);
        }
    }
}
