package ch.castleridge.javals.indexing.model;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Package-private structural-type flyweight tables used by {@link Type} factories. */
final class TypeCaches {

    private TypeCaches() {}

    static final ConcurrentMap<Type, Type.Array> ARRAY = new ConcurrentHashMap<>(1 << 10);
    static final ConcurrentMap<Type, Type.Wildcard> EXTENDS = new ConcurrentHashMap<>(1 << 8);
    static final ConcurrentMap<Type, Type.Wildcard> SUPER = new ConcurrentHashMap<>(1 << 8);
    static final ConcurrentMap<ParameterizedKey, Type.Parameterized> PARAMETERIZED =
            new ConcurrentHashMap<>(1 << 12);

    /**
     * Cache key for {@link Type.Parameterized}. Explicit array equality so
     * equal argument lists collide even when the {@code Type[]} instances
     * differ.
     */
    static final class ParameterizedKey {
        private final TypeRef raw;
        private final Type[] typeArgs;
        private final int hash;

        ParameterizedKey(TypeRef raw, Type[] typeArgs) {
            this.raw = raw;
            this.typeArgs = EmptyArrays.orEmpty(typeArgs, EmptyArrays.TYPE);
            this.hash = 31 * raw.hashCode() + Arrays.hashCode(this.typeArgs);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ParameterizedKey that)) return false;
            return hash == that.hash
                    && raw.equals(that.raw)
                    && Arrays.equals(typeArgs, that.typeArgs);
        }
    }
}
