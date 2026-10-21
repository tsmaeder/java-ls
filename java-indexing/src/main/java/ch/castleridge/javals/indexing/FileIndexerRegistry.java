/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ch.castleridge.javals.indexing.bytecode.BytecodeIndexer;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.source.SourceIndexer;
import ch.castleridge.javals.indexing.source.kotlin.KotlinSourceIndexer;

/**
 * Extension → {@link FileIndexer} dispatch used by walkers and {@code Scanner}.
 *
 * <p>Java source and classfile backends remain selectable via
 * {@link SourceIndexer} / {@link BytecodeIndexer}; this registry only maps
 * file types to the configured indexer for that type.
 */
public final class FileIndexerRegistry {

    public static final String EXT_JAVA = ".java";
    public static final String EXT_CLASS = ".class";
    public static final String EXT_KT = ".kt";

    private final Map<String, FileIndexer> byExtension;

    public FileIndexerRegistry(Collection<? extends FileIndexer> indexers) {
        Map<String, FileIndexer> map = new LinkedHashMap<>();
        if (indexers != null) {
            for (FileIndexer indexer : indexers) {
                if (indexer == null) continue;
                String ext = normalizeExtension(indexer.extension());
                if (ext.isEmpty()) continue;
                map.put(ext, indexer);
            }
        }
        this.byExtension = Map.copyOf(map);
    }

    public static FileIndexerRegistry defaults() {
        return of(SourceIndexer.javac(), BytecodeIndexer.asm());
    }

    public static FileIndexerRegistry of(SourceIndexer sourceIndexer, BytecodeIndexer bytecodeIndexer) {
        SourceIndexer source = sourceIndexer == null ? SourceIndexer.javac() : sourceIndexer;
        BytecodeIndexer bytecode = bytecodeIndexer == null ? BytecodeIndexer.asm() : bytecodeIndexer;
        return new FileIndexerRegistry(java.util.List.of(
                forJava(source),
                forClass(bytecode),
                KotlinSourceIndexer.INSTANCE));
    }

    public static FileIndexer forJava(SourceIndexer sourceIndexer) {
        Objects.requireNonNull(sourceIndexer, "sourceIndexer");
        return new FileIndexer() {
            @Override
            public String extension() {
                return EXT_JAVA;
            }

            @Override
            public void index(String resourcePath, String sourceUri, byte[] content, Index into) {
                sourceIndexer.index(resourcePath, sourceUri,
                        new String(content, StandardCharsets.UTF_8), into);
            }
        };
    }

    public static FileIndexer forClass(BytecodeIndexer bytecodeIndexer) {
        Objects.requireNonNull(bytecodeIndexer, "bytecodeIndexer");
        return new FileIndexer() {
            @Override
            public String extension() {
                return EXT_CLASS;
            }

            @Override
            public void index(String resourcePath, String sourceUri, byte[] content, Index into) {
                bytecodeIndexer.index(resourcePath, sourceUri, content, into);
            }
        };
    }

    public Set<String> extensions() {
        return byExtension.keySet();
    }

    public FileIndexer get(String extension) {
        return byExtension.get(normalizeExtension(extension));
    }

    /**
     * True when {@code fileName} has a registered extension and is not a
     * skipped package-info file.
     */
    public boolean accepts(String fileName) {
        if (fileName == null || Index.isSkippedFileName(fileName)) return false;
        return byExtension.containsKey(extensionOf(fileName));
    }

    /**
     * Dispatches {@code content} to the indexer for {@code fileName}'s
     * extension. No-op when the extension is unknown or the file is skipped.
     */
    public void indexOne(String resourcePath, String sourceUri, String fileName,
                         byte[] content, Index into) {
        if (!accepts(fileName)) return;
        FileIndexer indexer = byExtension.get(extensionOf(fileName));
        if (indexer != null) {
            indexer.index(resourcePath, sourceUri, content, into);
        }
    }

    /**
     * True when walkers should hand {@code fileName} to an indexer: known
     * extensions ({@code .java}, {@code .class}, {@code .kt}) and not a
     * skipped package-info file. Independent of a specific registry instance
     * so {@code DirInput}/{@code JarInput}/{@code JrtInput} stay lightweight.
     */
    public static boolean isIndexableFileName(String fileName) {
        if (fileName == null || Index.isSkippedFileName(fileName)) return false;
        String ext = extensionOf(fileName);
        return EXT_JAVA.equals(ext) || EXT_CLASS.equals(ext) || EXT_KT.equals(ext);
    }

    /** True for indexable source-like names ({@code .java}, {@code .kt}). */
    public static boolean isSourceFileName(String fileName) {
        if (fileName == null) return false;
        String ext = extensionOf(fileName);
        return EXT_JAVA.equals(ext) || EXT_KT.equals(ext);
    }

    /**
     * Extension of {@code fileName} including the leading dot, lower-cased;
     * empty string when there is no extension.
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String simple = slash < 0 ? fileName : fileName.substring(slash + 1);
        int dot = simple.lastIndexOf('.');
        if (dot < 0) return "";
        return simple.substring(dot).toLowerCase();
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) return "";
        String ext = extension.trim().toLowerCase();
        return ext.startsWith(".") ? ext : "." + ext;
    }
}
