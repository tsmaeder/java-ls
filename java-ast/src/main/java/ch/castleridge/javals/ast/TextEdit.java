/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * A range replacement against the original source. LSP mapping lives in
 * java-ls; this type is compiler- and protocol-neutral.
 */
public record TextEdit(int start, int end, String replacement) {

    public TextEdit {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("bad range " + start + ".." + end);
        }
        if (replacement == null) replacement = "";
    }

    public static String applyAll(String source, java.util.List<TextEdit> edits) {
        if (source == null) return "";
        if (edits == null || edits.isEmpty()) return source;
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (TextEdit edit : edits) {
            if (edit.start() < cursor) {
                throw new IllegalArgumentException("overlapping or unsorted edits");
            }
            out.append(source, cursor, edit.start());
            out.append(edit.replacement());
            cursor = edit.end();
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }
}
