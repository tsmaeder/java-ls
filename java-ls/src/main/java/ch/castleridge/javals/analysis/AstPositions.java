/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;

/**
 * LSP positions from a {@link SourceFile} buffer. Tabs count as one
 * character (same rule as the old javac {@code LspPositions} helper).
 */
public final class AstPositions {

    private AstPositions() {}

    public static Position positionAt(SourceFile source, int offset) {
        if (source == null) return new Position(0, 0);
        SourceFile.LineColumn lc = source.lineColumn(offset);
        return new Position(lc.line(), lc.character());
    }

    public static int offsetAt(SourceFile source, Position position) {
        if (source == null || position == null) return -1;
        return source.offsetAt(position.getLine(), position.getCharacter());
    }

    public static Range rangeOf(SourceFile source, SourceRange range) {
        return rangeOf(source, range, null);
    }

    public static Range rangeOf(SourceFile source, SourceRange range, SourceFile.Cursor cursor) {
        if (source == null || range == null || !range.isPresent()) {
            Position origin = new Position(0, 0);
            return new Range(origin, origin);
        }
        if (cursor == null) {
            return new Range(positionAt(source, range.start()), positionAt(source, range.end()));
        }
        SourceFile.LineColumn start = cursor.lineColumn(range.start());
        SourceFile.LineColumn end = cursor.lineColumn(range.end());
        return new Range(new Position(start.line(), start.character()),
                new Position(end.line(), end.character()));
    }

    public static Location location(String uri, SourceFile source, SourceRange range) {
        return location(uri, source, range, null);
    }

    public static Location location(String uri, SourceFile source, SourceRange range, SourceFile.Cursor cursor) {
        String documentUri = uri == null ? (source == null ? "" : source.uri()) : uri;
        return new Location(documentUri, rangeOf(source, range, cursor));
    }
}
