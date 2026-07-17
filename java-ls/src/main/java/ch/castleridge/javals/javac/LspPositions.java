package ch.castleridge.javals.javac;

import org.eclipse.lsp4j.Position;

import com.sun.source.tree.LineMap;

/**
 * Converts between javac file offsets and LSP {@link Position}s.
 *
 * <p>LSP {@code character} is a UTF-16 code-unit offset from the start of the
 * line. javac's {@link LineMap#getColumnNumber(long)} / {@link LineMap#getPosition}
 * instead count <em>visual</em> columns with tab expansion (tab stops every 8).
 * Mixing the two shifts ranges on lines that contain tabs — e.g. a
 * {@code String} type next to a tab-aligned field name highlights the field
 * name instead of {@code String}.
 */
public final class LspPositions {

    private LspPositions() {}

    /**
     * LSP position for a 0-based file offset. {@code character} is the raw
     * UTF-16 offset within the line (tabs count as one).
     */
    public static Position positionAt(LineMap lineMap, long offset) {
        long line = lineMap.getLineNumber(offset);
        long lineStart = lineMap.getStartPosition(line);
        long character = offset - lineStart;
        return new Position(toIntClamped(line - 1), toIntClamped(character));
    }

    /**
     * File offset for an LSP position. Inverse of {@link #positionAt}.
     * Returns {@code -1} when the line map is missing or the position is
     * out of range.
     */
    public static long offsetAt(LineMap lineMap, Position position) {
        if (lineMap == null || position == null) {
            return -1;
        }
        try {
            long lineStart = lineMap.getStartPosition(position.getLine() + 1L);
            long offset = lineStart + position.getCharacter();
            return offset < 0 ? -1 : offset;
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            return -1;
        }
    }

    private static int toIntClamped(long v) {
        if (v < 0) return 0;
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) v;
    }
}
