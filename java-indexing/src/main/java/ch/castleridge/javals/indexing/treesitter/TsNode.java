/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Named tree-sitter node rebuilt from a {@code tree_sitter_javals} compact
 * tree payload. Byte ranges index into the request UTF-8 source.
 */
public final class TsNode {

    private final String type;
    private final int startByte;
    private final int endByte;
    private final List<TsNode> children;
    private final byte[] sourceUtf8;

    public TsNode(String type, int startByte, int endByte, List<TsNode> children, byte[] sourceUtf8) {
        this.type = type;
        this.startByte = startByte;
        this.endByte = endByte;
        this.children = children == null || children.isEmpty() ? List.of() : List.copyOf(children);
        this.sourceUtf8 = sourceUtf8;
    }

    public String type() {
        return type;
    }

    public int startByte() {
        return startByte;
    }

    public int endByte() {
        return endByte;
    }

    public List<TsNode> namedChildren() {
        return children;
    }

    /** UTF-8 source slice {@code [startByte, endByte)}. */
    public String text() {
        if (sourceUtf8 == null || startByte < 0 || endByte < startByte
                || endByte > sourceUtf8.length) {
            return "";
        }
        return new String(sourceUtf8, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    /** UTF-8 source slice {@code [fromByte, toByte)} clipped to this node's source. */
    public String textSlice(int fromByte, int toByte) {
        if (sourceUtf8 == null) return "";
        int from = Math.max(0, Math.min(fromByte, sourceUtf8.length));
        int to = Math.max(from, Math.min(toByte, sourceUtf8.length));
        return new String(sourceUtf8, from, to - from, StandardCharsets.UTF_8);
    }
}
