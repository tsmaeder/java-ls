/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.ArrayList;
import java.util.List;

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

    record Declared(String jvmBinaryName, List<JType> typeArgs) implements JType {
        public Declared {
            if (jvmBinaryName == null || jvmBinaryName.isEmpty()) {
                throw new IllegalArgumentException("jvmBinaryName");
            }
            typeArgs = typeArgs == null || typeArgs.isEmpty() ? List.of() : List.copyOf(typeArgs);
        }

        public static Declared of(String jvmBinaryName) {
            return new Declared(jvmBinaryName, List.of());
        }

        public String binaryName() {
            return jvmBinaryName.replace('/', '.');
        }

        @Override
        public String toString() {
            String name = binaryName().replace('$', '.');
            if (typeArgs.isEmpty()) return name;
            StringBuilder sb = new StringBuilder(name).append('<');
            for (int i = 0; i < typeArgs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(typeArgs.get(i));
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

    record Intersection(List<JType> bounds) implements JType {
        public Intersection {
            bounds = bounds == null ? List.of() : List.copyOf(bounds);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bounds.size(); i++) {
                if (i > 0) sb.append('&');
                sb.append(bounds.get(i));
            }
            return sb.toString();
        }
    }

    record Union(List<JType> alternatives) implements JType {
        public Union {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < alternatives.size(); i++) {
                if (i > 0) sb.append('|');
                sb.append(alternatives.get(i));
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
