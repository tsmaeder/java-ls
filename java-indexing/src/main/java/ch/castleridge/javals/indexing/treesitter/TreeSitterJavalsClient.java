/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.treesitter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-thread {@code tree_sitter_javals} side process speaking the length-prefixed
 * little-endian stdin/stdout protocol.
 */
public final class TreeSitterJavalsClient implements AutoCloseable {

    private static final ThreadLocal<TreeSitterJavalsClient> PER_THREAD =
            ThreadLocal.withInitial(TreeSitterJavalsClient::new);

    private Process process;
    private OutputStream stdin;
    private InputStream stdout;

    private TreeSitterJavalsClient() {}

    /** Client bound to the current thread (one long-lived process). */
    public static TreeSitterJavalsClient current() {
        return PER_THREAD.get();
    }

    /**
     * Parses {@code contentUtf8} as {@code language} and returns the named-node
     * root, or empty on protocol/parse failure.
     */
    public Optional<TsNode> parse(String language, String uri, byte[] contentUtf8) {
        if (language == null || uri == null || contentUtf8 == null) {
            return Optional.empty();
        }
        try {
            ensureProcess();
            writeRequest(language, uri, contentUtf8);
            return Optional.ofNullable(readSuccessRoot(contentUtf8));
        } catch (IOException | RuntimeException e) {
            destroyProcess();
            try {
                ensureProcess();
                writeRequest(language, uri, contentUtf8);
                return Optional.ofNullable(readSuccessRoot(contentUtf8));
            } catch (IOException | RuntimeException retry) {
                destroyProcess();
                System.err.println("tree_sitter_javals parse failed: "
                        + retry.getClass().getSimpleName() + ": " + retry.getMessage());
                return Optional.empty();
            }
        }
    }

    public Optional<TsNode> parse(String language, String uri, String content) {
        if (content == null) return Optional.empty();
        return parse(language, uri, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        destroyProcess();
    }

    private void ensureProcess() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        destroyProcess();
        Path exe = TreeSitterNatives.executable();
        ProcessBuilder pb = new ProcessBuilder(exe.toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        stdin = process.getOutputStream();
        stdout = process.getInputStream();
    }

    private void destroyProcess() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        stdin = null;
        stdout = null;
    }

    private void writeRequest(String language, String uri, byte[] contentUtf8) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        LittleEndian.writeString(body, language);
        LittleEndian.writeString(body, uri);
        LittleEndian.writeBytes(body, contentUtf8);
        byte[] bodyBytes = body.toByteArray();
        LittleEndian.writeU32(stdin, bodyBytes.length);
        stdin.write(bodyBytes);
        stdin.flush();
    }

    private TsNode readSuccessRoot(byte[] contentUtf8) throws IOException {
        int frameLen = LittleEndian.readU32(stdout);
        if (frameLen < 1) {
            throw new IOException("Empty response frame");
        }
        byte[] frame = LittleEndian.readBytes(stdout, frameLen);
        ByteArrayInputStream in = new ByteArrayInputStream(frame);
        int status = in.read();
        if (status < 0) {
            throw new IOException("Truncated response status");
        }
        if (status != 0) {
            String message = LittleEndian.readString(in);
            throw new IOException("tree_sitter_javals status=" + status + ": " + message);
        }
        // Skip identifier list — host walks the tree for bloom (includes type_identifier).
        int idCount = LittleEndian.readU32(in);
        if (idCount < 0) {
            throw new IOException("Negative identifier_count");
        }
        for (int i = 0; i < idCount; i++) {
            LittleEndian.readString(in);
        }
        int treeLen = LittleEndian.readU32(in);
        if (treeLen < 0) {
            throw new IOException("Negative tree_byte_len");
        }
        byte[] treePayload = LittleEndian.readBytes(in, treeLen);
        return decodeTree(treePayload, contentUtf8);
    }

    static TsNode decodeTree(byte[] treePayload, byte[] contentUtf8) throws IOException {
        int offset = 0;
        if (treePayload.length < 4) {
            throw new IOException("Truncated tree payload");
        }
        int typeCount = LittleEndian.u32At(treePayload, offset);
        offset += 4;
        if (typeCount < 0) {
            throw new IOException("Negative type_table_count");
        }
        String[] types = new String[typeCount];
        for (int i = 0; i < typeCount; i++) {
            if (offset + 4 > treePayload.length) {
                throw new IOException("Truncated type table");
            }
            int nameLen = LittleEndian.u32At(treePayload, offset);
            offset += 4;
            if (nameLen < 0 || offset + nameLen > treePayload.length) {
                throw new IOException("Invalid type name length");
            }
            types[i] = new String(treePayload, offset, nameLen, StandardCharsets.UTF_8);
            offset += nameLen;
        }
        if (offset + 4 > treePayload.length) {
            throw new IOException("Truncated node_count");
        }
        int nodeCount = LittleEndian.u32At(treePayload, offset);
        offset += 4;
        if (nodeCount < 0) {
            throw new IOException("Negative node_count");
        }
        if (nodeCount == 0) {
            return null;
        }

        int[] typeIndexes = new int[nodeCount];
        int[] starts = new int[nodeCount];
        int[] ends = new int[nodeCount];
        int[] childCounts = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            if (offset + 16 > treePayload.length) {
                throw new IOException("Truncated node records");
            }
            int typeIndex = LittleEndian.u32At(treePayload, offset);
            int start = LittleEndian.u32At(treePayload, offset + 4);
            int end = LittleEndian.u32At(treePayload, offset + 8);
            int childCount = LittleEndian.u32At(treePayload, offset + 12);
            offset += 16;
            if (typeIndex < 0 || typeIndex >= typeCount) {
                throw new IOException("type_index out of range: " + typeIndex);
            }
            if (childCount < 0) {
                throw new IOException("Negative named_child_count");
            }
            typeIndexes[i] = typeIndex;
            starts[i] = start;
            ends[i] = end;
            childCounts[i] = childCount;
        }

        int[] cursor = {0};
        TsNode root = buildNode(typeIndexes, starts, ends, childCounts, types, contentUtf8, cursor);
        if (cursor[0] != nodeCount) {
            throw new IOException("Tree child counts do not consume all nodes");
        }
        return root;
    }

    private static TsNode buildNode(
            int[] typeIndexes,
            int[] starts,
            int[] ends,
            int[] childCounts,
            String[] types,
            byte[] source,
            int[] cursor) throws IOException {
        int i = cursor[0]++;
        if (i >= typeIndexes.length) {
            throw new IOException("Node index out of range while building tree");
        }
        int childCount = childCounts[i];
        List<TsNode> children;
        if (childCount == 0) {
            children = List.of();
        } else {
            children = new ArrayList<>(childCount);
            for (int c = 0; c < childCount; c++) {
                children.add(buildNode(typeIndexes, starts, ends, childCounts, types, source, cursor));
            }
        }
        return new TsNode(types[typeIndexes[i]], starts[i], ends[i], children, source);
    }
}
