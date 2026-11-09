/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 */
package ch.castleridge.javals.indexing.source;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;

/**
 * Folds same-type {@code static final} constant expressions into
 * {@link FieldEntry#constantValue()} at index time.
 *
 * <p>Indexing stays classpath-free: only literals and peers of the type being
 * indexed participate. Cross-type aliases remain as
 * {@code constantOwner}/{@code constantName} for analysis-time folding.
 */
public final class SameTypeConstantFolder {

    private SameTypeConstantFolder() {}

    /**
     * Look up a compile-time constant among same-type peers. Follows same-type
     * field-ref aliases ({@code constantOwner == null}); does not resolve
     * cross-type owners.
     */
    public static Object resolvePeer(String name, FieldEntry[] peers) {
        return resolvePeer(name, peers, new HashSet<>());
    }

    private static Object resolvePeer(String name, FieldEntry[] peers, Set<String> visiting) {
        if (name == null || !visiting.add(name)) {
            return null;
        }
        for (FieldEntry peer : peers) {
            if (!name.equals(peer.name())) {
                continue;
            }
            if (peer.constantValue() != null) {
                return peer.constantValue();
            }
            if (peer.constantOwner() == null && peer.constantName() != null) {
                return resolvePeer(peer.constantName(), peers, visiting);
            }
            return null;
        }
        return null;
    }

    /**
     * JLS-style {@code +} on two folded constant operands (string concat or
     * numeric add). Returns {@code null} when the operands cannot be combined.
     */
    public static Object plus(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left) + String.valueOf(right);
        }
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
            return null;
        }
        if (left instanceof Double || right instanceof Double) {
            return leftNumber.doubleValue() + rightNumber.doubleValue();
        }
        if (left instanceof Float || right instanceof Float) {
            return leftNumber.floatValue() + rightNumber.floatValue();
        }
        if (left instanceof Long || right instanceof Long) {
            return leftNumber.longValue() + rightNumber.longValue();
        }
        return leftNumber.intValue() + rightNumber.intValue();
    }

    /** Replace a field's constant slots with a folded literal. */
    public static FieldEntry withFoldedLiteral(FieldEntry field, Object value) {
        return new FieldEntry(
                field.modifiers(),
                field.name(),
                field.type(),
                value,
                null,
                null,
                field.annotations());
    }

    /**
     * Fixed-point pass: for each field without a {@code constantValue} and
     * without a cross-type alias, ask {@code foldInitializer} (and same-type
     * {@code constantName} aliases) for a literal.
     *
     * @param foldInitializer {@code (fieldIndex, currentPeers) -> folded literal or null}
     */
    public static void foldFixedPoint(
            List<FieldEntry> fields,
            BiFunction<Integer, FieldEntry[], Object> foldInitializer) {
        boolean progressed;
        do {
            progressed = false;
            for (int i = 0; i < fields.size(); i++) {
                FieldEntry current = fields.get(i);
                if (current.constantValue() != null || current.constantOwner() != null) {
                    continue;
                }
                FieldEntry[] peers = fields.toArray(EmptyArrays.FIELD);
                Object folded = foldInitializer.apply(i, peers);
                if (folded == null && current.constantName() != null) {
                    folded = resolvePeer(current.constantName(), peers);
                }
                if (folded != null) {
                    fields.set(i, withFoldedLiteral(current, folded));
                    progressed = true;
                }
            }
        } while (progressed);
    }
}
