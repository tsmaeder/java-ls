/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * Resolved type on expressions and type-use nodes. Independent of the
 * indexer's {@code Type} model (which still carries unresolved names).
 */
public sealed interface JType
        permits JType.Primitive,
                JType.VoidType,
                JType.NullType,
                JType.ErrorType,
                JType.Array,
                JType.Declared,
                JType.TypeVar,
                JType.Wildcard,
                JType.Intersection,
                JType.Union,
                JType.Capture {

    VoidType VOID = new VoidType();
    NullType NULL = new NullType();
    ErrorType ERROR = new ErrorType("");

    enum Primitive implements JType {
        BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    record VoidType() implements JType {
        @Override
        public String toString() {
            return "void";
        }
    }

    record NullType() implements JType {
        @Override
        public String toString() {
            return "<null>";
        }
    }

    record ErrorType(String message) implements JType {
        public ErrorType {
            if (message == null) message = "";
        }

        @Override
        public String toString() {
            return message.isEmpty() ? "<error>" : "<error:" + message + ">";
        }
    }

    record Array(JType element) implements JType {
        public Array {
            if (element == null) element = ERROR;
        }

        @Override
        public String toString() {
            return element + "[]";
        }
    }

    record Declared(String jvmBinaryName, JType[] typeArgs) implements JType {
        public Declared {
            if (jvmBinaryName == null || jvmBinaryName.isEmpty()) {
                throw new IllegalArgumentException("jvmBinaryName");
            }
            typeArgs = EmptyArrays.orEmpty(typeArgs, EmptyArrays.JTYPE);
        }

        public static Declared of(String jvmBinaryName) {
            return new Declared(jvmBinaryName, EmptyArrays.JTYPE);
        }

        public String binaryName() {
            return jvmBinaryName.replace('/', '.');
        }

        @Override
        public String toString() {
            String name = binaryName().replace('$', '.');
            if (typeArgs.length == 0) return name;
            StringBuilder sb = new StringBuilder(name).append('<');
            for (int i = 0; i < typeArgs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(typeArgs[i]);
            }
            return sb.append('>').toString();
        }
    }

    record TypeVar(String name) implements JType {
        public TypeVar {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name");
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record Wildcard(BoundKind kind, JType bound) implements JType {
        public enum BoundKind { UNBOUNDED, EXTENDS, SUPER }

        public static Wildcard unbounded() {
            return new Wildcard(BoundKind.UNBOUNDED, null);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + bound;
                case SUPER -> "? super " + bound;
            };
        }
    }

    record Intersection(JType[] bounds) implements JType {
        public Intersection {
            bounds = EmptyArrays.orEmpty(bounds, EmptyArrays.JTYPE);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bounds.length; i++) {
                if (i > 0) sb.append('&');
                sb.append(bounds[i]);
            }
            return sb.toString();
        }
    }

    record Union(JType[] alternatives) implements JType {
        public Union {
            alternatives = EmptyArrays.orEmpty(alternatives, EmptyArrays.JTYPE);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < alternatives.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(alternatives[i]);
            }
            return sb.toString();
        }
    }

    record Capture(Wildcard wildcard) implements JType {
        public Capture {
            if (wildcard == null) wildcard = Wildcard.unbounded();
        }

        @Override
        public String toString() {
            return "capture of " + wildcard;
        }
    }

    static Array array(JType element) {
        return new Array(element);
    }

    default JType array() {
        return new Array(this);
    }
}
