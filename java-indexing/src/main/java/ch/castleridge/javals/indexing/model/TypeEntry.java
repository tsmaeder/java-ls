/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

/**
 * Common surface for indexed type declarations regardless of origin
 * (source parser or classfile parser). Origin-specific payload is carried
 * by {@link SourceTypeEntry} and {@link ClassFileTypeEntry}.
 *
 * <p>{@link #resourceUri()} is the full resource location, reconstructed
 * from the compact {@code resourcePath} stored on the concrete entry and
 * {@link #sourceUri()} when compaction is loss-free (see {@link ResourceUris}).
 */
public sealed interface TypeEntry extends IndexEntry permits SourceTypeEntry, ClassFileTypeEntry {
    String resourceUri();

    String sourceUri();

    String jvmOwnerName();

    Type superRef();

    Type[] interfaceRefs();

    TypeParamRef[] typeParams();

    FieldEntry[] fields();

    MethodEntry[] methods();

    String[] innerTypeJvmNames();

    TypeRef[] permittedSubclasses();

    RecordComponentEntry[] recordComponents();

    default String jvmName() {
        return jvmOwnerName();
    }

    default String packageJvm() {
        String owner = jvmOwnerName();
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    @Override
    default EntryKind kind() {
        return EntryKind.TYPE;
    }
}
