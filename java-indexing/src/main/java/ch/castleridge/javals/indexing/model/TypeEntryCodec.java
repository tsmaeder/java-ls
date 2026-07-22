package ch.castleridge.javals.indexing.model;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compact binary encoding of {@link SourceTypeEntry} / {@link ClassFileTypeEntry}.
 *
 * <p>Strings are stored as ids into the process-wide {@link StringTable}.
 * Structural {@link Type} shapes are rebuilt through the existing factories
 * ({@link TypeRef#resolved}, {@link Type#array}, …) so the flyweight caches
 * stay shared. Decode always returns the concrete record types consumers
 * pattern-match on.
 */
public final class TypeEntryCodec {

    private static final byte KIND_SOURCE = 0;
    private static final byte KIND_CLASSFILE = 1;

    // Type tags
    private static final byte T_NULL = 0;
    private static final byte T_PRIMITIVE = 1;
    private static final byte T_ARRAY = 2;
    private static final byte T_TYPE_VAR = 3;
    private static final byte T_WILDCARD = 4;
    private static final byte T_PARAMETERIZED = 5;
    private static final byte T_ANNOTATED = 6;
    private static final byte T_RESOLVED = 7;
    private static final byte T_UNRESOLVED = 8;

    // AnnotationValue tags
    private static final byte AV_PRIMITIVE = 1;
    private static final byte AV_STR = 2;
    private static final byte AV_CLASS_REF = 3;
    private static final byte AV_ENUM_CONST = 4;
    private static final byte AV_ARR = 5;
    private static final byte AV_NESTED = 6;
    private static final byte AV_UNSUPPORTED = 7;

    // Boxed constant / AnnotationValue.Primitive tags
    private static final byte BOX_NULL = 0;
    private static final byte BOX_BOOLEAN = 1;
    private static final byte BOX_BYTE = 2;
    private static final byte BOX_SHORT = 3;
    private static final byte BOX_CHAR = 4;
    private static final byte BOX_INT = 5;
    private static final byte BOX_LONG = 6;
    private static final byte BOX_FLOAT = 7;
    private static final byte BOX_DOUBLE = 8;
    private static final byte BOX_STRING = 9;

    private TypeEntryCodec() {}

    public static byte[] encode(TypeEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        Writer w = new Writer();
        if (entry instanceof SourceTypeEntry source) {
            w.writeByte(KIND_SOURCE);
            writeCommon(w, source.resourcePath(), source.sourceUri(), source.jvmOwnerName(),
                    source.modifiers(), source.superRef(), source.interfaceRefs(),
                    source.typeParams(), source.fields(), source.methods(),
                    source.innerTypeJvmNames(), source.permittedSubclasses(),
                    source.recordComponents(), source.annotations());
            w.writeByte((byte) source.declKind().ordinal());
            writeHints(w, source.hints());
        } else if (entry instanceof ClassFileTypeEntry classFile) {
            w.writeByte(KIND_CLASSFILE);
            writeCommon(w, classFile.resourcePath(), classFile.sourceUri(), classFile.jvmOwnerName(),
                    classFile.modifiers(), classFile.superRef(), classFile.interfaceRefs(),
                    classFile.typeParams(), classFile.fields(), classFile.methods(),
                    classFile.innerTypeJvmNames(), classFile.permittedSubclasses(),
                    classFile.recordComponents(), classFile.annotations());
        } else {
            throw new IllegalArgumentException("unsupported TypeEntry: " + entry.getClass().getName());
        }
        return w.toByteArray();
    }

    public static TypeEntry decode(byte[] blob) {
        if (blob == null || blob.length == 0) {
            throw new IllegalArgumentException("blob must be non-empty");
        }
        Reader r = new Reader(blob);
        byte kind = r.readByte();
        String resourcePath = r.readString();
        String sourceUri = r.readString();
        String jvmOwnerName = r.readString();
        int modifiers = r.readVarInt();
        Type superRef = readType(r);
        Type[] interfaceRefs = readTypeArray(r);
        TypeParamRef[] typeParams = readTypeParams(r);
        FieldEntry[] fields = readFields(r);
        MethodEntry[] methods = readMethods(r);
        String[] innerTypeJvmNames = readStringArray(r);
        TypeRef[] permittedSubclasses = readTypeRefArray(r);
        RecordComponentEntry[] recordComponents = readRecordComponents(r);
        AnnotationRef[] annotations = readAnnotations(r);
        return switch (kind) {
            case KIND_SOURCE -> {
                TypeDeclKind declKind = TypeDeclKind.values()[r.readByte() & 0xFF];
                SourceResolutionHints hints = readHints(r);
                yield new SourceTypeEntry(
                        resourcePath, sourceUri, jvmOwnerName, modifiers, declKind,
                        superRef, interfaceRefs, typeParams, fields, methods,
                        innerTypeJvmNames, permittedSubclasses, recordComponents,
                        annotations, hints);
            }
            case KIND_CLASSFILE -> new ClassFileTypeEntry(
                    resourcePath, sourceUri, jvmOwnerName, modifiers,
                    superRef, interfaceRefs, typeParams, fields, methods,
                    innerTypeJvmNames, permittedSubclasses, recordComponents, annotations);
            default -> throw new IllegalArgumentException("unknown TypeEntry kind: " + kind);
        };
    }

    private static void writeCommon(
            Writer w,
            String resourcePath,
            String sourceUri,
            String jvmOwnerName,
            int modifiers,
            Type superRef,
            Type[] interfaceRefs,
            TypeParamRef[] typeParams,
            FieldEntry[] fields,
            MethodEntry[] methods,
            String[] innerTypeJvmNames,
            TypeRef[] permittedSubclasses,
            RecordComponentEntry[] recordComponents,
            AnnotationRef[] annotations) {
        w.writeString(resourcePath);
        w.writeString(sourceUri);
        w.writeString(jvmOwnerName);
        w.writeVarInt(modifiers);
        writeType(w, superRef);
        writeTypeArray(w, interfaceRefs);
        writeTypeParams(w, typeParams);
        writeFields(w, fields);
        writeMethods(w, methods);
        writeStringArray(w, innerTypeJvmNames);
        writeTypeRefArray(w, permittedSubclasses);
        writeRecordComponents(w, recordComponents);
        writeAnnotations(w, annotations);
    }

    // --- Type ---

    private static void writeType(Writer w, Type type) {
        if (type == null) {
            w.writeByte(T_NULL);
            return;
        }
        if (type instanceof Type.Annotated a) {
            w.writeByte(T_ANNOTATED);
            writeType(w, a.inner());
            writeAnnotations(w, a.annotations());
            return;
        }
        if (type instanceof Type.Primitive p) {
            w.writeByte(T_PRIMITIVE);
            w.writeByte((byte) p.ordinal());
            return;
        }
        if (type instanceof Type.Array a) {
            w.writeByte(T_ARRAY);
            writeType(w, a.element());
            return;
        }
        if (type instanceof Type.TypeVariable tv) {
            w.writeByte(T_TYPE_VAR);
            w.writeString(tv.name());
            return;
        }
        if (type instanceof Type.Wildcard wild) {
            w.writeByte(T_WILDCARD);
            w.writeByte((byte) wild.kind().ordinal());
            writeType(w, wild.bound());
            return;
        }
        if (type instanceof Type.Parameterized p) {
            w.writeByte(T_PARAMETERIZED);
            writeType(w, p.raw());
            writeTypeArray(w, p.typeArgs());
            return;
        }
        if (type instanceof TypeRef.Resolved resolved) {
            w.writeByte(T_RESOLVED);
            w.writeString(resolved.jvmBinaryName());
            return;
        }
        if (type instanceof TypeRef.Unresolved unresolved) {
            w.writeByte(T_UNRESOLVED);
            w.writeString(unresolved.simpleName());
            return;
        }
        throw new IllegalArgumentException("unsupported Type: " + type.getClass().getName());
    }

    private static Type readType(Reader r) {
        byte tag = r.readByte();
        return switch (tag) {
            case T_NULL -> null;
            case T_PRIMITIVE -> Type.Primitive.values()[r.readByte() & 0xFF];
            case T_ARRAY -> Type.array(readType(r));
            case T_TYPE_VAR -> Type.typeVariable(r.readString());
            case T_WILDCARD -> {
                Type.Wildcard.BoundKind kind = Type.Wildcard.BoundKind.values()[r.readByte() & 0xFF];
                Type bound = readType(r);
                yield switch (kind) {
                    case UNBOUNDED -> Type.Wildcard.unbounded();
                    case EXTENDS -> Type.Wildcard.extendsBound(bound);
                    case SUPER -> Type.Wildcard.superBound(bound);
                };
            }
            case T_PARAMETERIZED -> {
                Type raw = readType(r);
                Type[] args = readTypeArray(r);
                yield Type.parameterized((TypeRef) raw, args);
            }
            case T_ANNOTATED -> Type.Annotated.wrap(readType(r), readAnnotations(r));
            case T_RESOLVED -> TypeRef.resolved(r.readString());
            case T_UNRESOLVED -> TypeRef.unresolved(r.readString());
            default -> throw new IllegalArgumentException("unknown Type tag: " + tag);
        };
    }

    private static void writeTypeArray(Writer w, Type[] types) {
        if (types == null || types.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(types.length);
        for (Type t : types) writeType(w, t);
    }

    private static Type[] readTypeArray(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.TYPE;
        Type[] out = new Type[n];
        for (int i = 0; i < n; i++) out[i] = readType(r);
        return out;
    }

    private static void writeTypeRefArray(Writer w, TypeRef[] refs) {
        if (refs == null || refs.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(refs.length);
        for (TypeRef ref : refs) writeType(w, ref);
    }

    private static TypeRef[] readTypeRefArray(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.TYPE_REF;
        TypeRef[] out = new TypeRef[n];
        for (int i = 0; i < n; i++) out[i] = (TypeRef) readType(r);
        return out;
    }

    // --- TypeParamRef ---

    private static void writeTypeParams(Writer w, TypeParamRef[] params) {
        if (params == null || params.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(params.length);
        for (TypeParamRef tp : params) {
            w.writeString(tp.name());
            writeTypeArray(w, tp.bounds());
        }
    }

    private static TypeParamRef[] readTypeParams(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.TYPE_PARAM;
        TypeParamRef[] out = new TypeParamRef[n];
        for (int i = 0; i < n; i++) {
            out[i] = new TypeParamRef(r.readString(), readTypeArray(r));
        }
        return out;
    }

    // --- FieldEntry ---

    private static void writeFields(Writer w, FieldEntry[] fields) {
        if (fields == null || fields.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(fields.length);
        for (FieldEntry f : fields) {
            w.writeVarInt(f.modifiers());
            w.writeString(f.name());
            writeType(w, f.type());
            writeBoxed(w, f.constantValue());
            writeAnnotations(w, f.annotations());
        }
    }

    private static FieldEntry[] readFields(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.FIELD;
        FieldEntry[] out = new FieldEntry[n];
        for (int i = 0; i < n; i++) {
            out[i] = new FieldEntry(
                    r.readVarInt(),
                    r.readString(),
                    readType(r),
                    readBoxed(r),
                    readAnnotations(r));
        }
        return out;
    }

    // --- MethodEntry ---

    private static void writeMethods(Writer w, MethodEntry[] methods) {
        if (methods == null || methods.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(methods.length);
        for (MethodEntry m : methods) {
            w.writeVarInt(m.modifiers());
            w.writeString(m.name());
            writeType(w, m.returnType());
            writeParameters(w, m.parameters());
            writeTypeArray(w, m.throwsTypes());
            writeTypeParams(w, m.typeParams());
            w.writeByte((byte) ((m.varargs() ? 1 : 0) | (m.hasBody() ? 2 : 0)));
            writeAnnotationValue(w, m.annotationDefault());
            writeAnnotations(w, m.annotations());
        }
    }

    private static MethodEntry[] readMethods(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.METHOD;
        MethodEntry[] out = new MethodEntry[n];
        for (int i = 0; i < n; i++) {
            int modifiers = r.readVarInt();
            String name = r.readString();
            Type returnType = readType(r);
            ParameterEntry[] parameters = readParameters(r);
            Type[] throwsTypes = readTypeArray(r);
            TypeParamRef[] typeParams = readTypeParams(r);
            int flags = r.readByte() & 0xFF;
            AnnotationValue annotationDefault = readAnnotationValue(r);
            AnnotationRef[] annotations = readAnnotations(r);
            out[i] = new MethodEntry(
                    modifiers, name, returnType, parameters, throwsTypes, typeParams,
                    (flags & 1) != 0, (flags & 2) != 0, annotationDefault, annotations);
        }
        return out;
    }

    private static void writeParameters(Writer w, ParameterEntry[] params) {
        if (params == null || params.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(params.length);
        for (ParameterEntry p : params) {
            w.writeString(p.name());
            w.writeVarInt(p.modifiers());
            writeType(w, p.type());
            writeAnnotations(w, p.annotations());
        }
    }

    private static ParameterEntry[] readParameters(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.PARAMETER;
        ParameterEntry[] out = new ParameterEntry[n];
        for (int i = 0; i < n; i++) {
            out[i] = new ParameterEntry(
                    r.readString(), r.readVarInt(), readType(r), readAnnotations(r));
        }
        return out;
    }

    // --- RecordComponentEntry ---

    private static void writeRecordComponents(Writer w, RecordComponentEntry[] components) {
        if (components == null || components.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(components.length);
        for (RecordComponentEntry rc : components) {
            w.writeString(rc.name());
            writeType(w, rc.type());
            writeAnnotations(w, rc.annotations());
        }
    }

    private static RecordComponentEntry[] readRecordComponents(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.RECORD_COMPONENT;
        RecordComponentEntry[] out = new RecordComponentEntry[n];
        for (int i = 0; i < n; i++) {
            out[i] = new RecordComponentEntry(r.readString(), readType(r), readAnnotations(r));
        }
        return out;
    }

    // --- AnnotationRef / AnnotationValue ---

    private static void writeAnnotations(Writer w, AnnotationRef[] annotations) {
        if (annotations == null || annotations.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(annotations.length);
        for (AnnotationRef a : annotations) writeAnnotation(w, a);
    }

    private static AnnotationRef[] readAnnotations(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.ANNOTATION_REF;
        AnnotationRef[] out = new AnnotationRef[n];
        for (int i = 0; i < n; i++) out[i] = readAnnotation(r);
        return out;
    }

    private static void writeAnnotation(Writer w, AnnotationRef a) {
        writeType(w, a.annotationType());
        Map<String, AnnotationValue> values = a.values();
        w.writeVarInt(values.size());
        for (Map.Entry<String, AnnotationValue> e : values.entrySet()) {
            w.writeString(e.getKey());
            writeAnnotationValue(w, e.getValue());
        }
    }

    private static AnnotationRef readAnnotation(Reader r) {
        TypeRef type = (TypeRef) readType(r);
        int n = r.readVarInt();
        if (n == 0) return new AnnotationRef(type, Map.of());
        Map<String, AnnotationValue> values = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            values.put(r.readString(), readAnnotationValue(r));
        }
        return new AnnotationRef(type, values);
    }

    private static void writeAnnotationValue(Writer w, AnnotationValue value) {
        if (value == null) {
            w.writeByte(BOX_NULL);
            return;
        }
        if (value instanceof AnnotationValue.Primitive p) {
            w.writeByte(AV_PRIMITIVE);
            writeBoxed(w, p.boxed());
            return;
        }
        if (value instanceof AnnotationValue.Str s) {
            w.writeByte(AV_STR);
            w.writeString(s.value());
            return;
        }
        if (value instanceof AnnotationValue.ClassRef c) {
            w.writeByte(AV_CLASS_REF);
            writeType(w, c.type());
            return;
        }
        if (value instanceof AnnotationValue.EnumConst e) {
            w.writeByte(AV_ENUM_CONST);
            writeType(w, e.enumType());
            w.writeString(e.constant());
            return;
        }
        if (value instanceof AnnotationValue.Arr a) {
            w.writeByte(AV_ARR);
            AnnotationValue[] elements = a.elements();
            w.writeVarInt(elements.length);
            for (AnnotationValue el : elements) writeAnnotationValue(w, el);
            return;
        }
        if (value instanceof AnnotationValue.Nested nested) {
            w.writeByte(AV_NESTED);
            writeAnnotation(w, nested.annotation());
            return;
        }
        if (value instanceof AnnotationValue.Unsupported u) {
            w.writeByte(AV_UNSUPPORTED);
            w.writeString(u.reason());
            return;
        }
        throw new IllegalArgumentException("unsupported AnnotationValue: " + value.getClass().getName());
    }

    private static AnnotationValue readAnnotationValue(Reader r) {
        byte tag = r.readByte();
        if (tag == BOX_NULL) return null;
        return switch (tag) {
            case AV_PRIMITIVE -> new AnnotationValue.Primitive(readBoxed(r));
            case AV_STR -> new AnnotationValue.Str(r.readString());
            case AV_CLASS_REF -> new AnnotationValue.ClassRef(readType(r));
            case AV_ENUM_CONST -> new AnnotationValue.EnumConst(readType(r), r.readString());
            case AV_ARR -> {
                int n = r.readVarInt();
                if (n == 0) yield new AnnotationValue.Arr(EmptyArrays.ANNOTATION_VALUE);
                AnnotationValue[] elements = new AnnotationValue[n];
                for (int i = 0; i < n; i++) elements[i] = readAnnotationValue(r);
                yield new AnnotationValue.Arr(elements);
            }
            case AV_NESTED -> new AnnotationValue.Nested(readAnnotation(r));
            case AV_UNSUPPORTED -> new AnnotationValue.Unsupported(r.readString());
            default -> throw new IllegalArgumentException("unknown AnnotationValue tag: " + tag);
        };
    }

    // --- Boxed constants ---

    private static void writeBoxed(Writer w, Object value) {
        if (value == null) {
            w.writeByte(BOX_NULL);
            return;
        }
        if (value instanceof Boolean b) {
            w.writeByte(BOX_BOOLEAN);
            w.writeByte((byte) (b ? 1 : 0));
        } else if (value instanceof Byte b) {
            w.writeByte(BOX_BYTE);
            w.writeByte(b);
        } else if (value instanceof Short s) {
            w.writeByte(BOX_SHORT);
            w.writeVarInt(s);
        } else if (value instanceof Character c) {
            w.writeByte(BOX_CHAR);
            w.writeVarInt(c);
        } else if (value instanceof Integer i) {
            w.writeByte(BOX_INT);
            w.writeVarInt(i);
        } else if (value instanceof Long l) {
            w.writeByte(BOX_LONG);
            w.writeVarLong(l);
        } else if (value instanceof Float f) {
            w.writeByte(BOX_FLOAT);
            w.writeVarInt(Float.floatToIntBits(f));
        } else if (value instanceof Double d) {
            w.writeByte(BOX_DOUBLE);
            w.writeVarLong(Double.doubleToLongBits(d));
        } else if (value instanceof String s) {
            w.writeByte(BOX_STRING);
            w.writeString(s);
        } else {
            throw new IllegalArgumentException(
                    "unsupported boxed value: " + value.getClass().getName());
        }
    }

    private static Object readBoxed(Reader r) {
        byte tag = r.readByte();
        return switch (tag) {
            case BOX_NULL -> null;
            case BOX_BOOLEAN -> r.readByte() != 0;
            case BOX_BYTE -> r.readByte();
            case BOX_SHORT -> (short) r.readVarInt();
            case BOX_CHAR -> (char) r.readVarInt();
            case BOX_INT -> r.readVarInt();
            case BOX_LONG -> r.readVarLong();
            case BOX_FLOAT -> Float.intBitsToFloat(r.readVarInt());
            case BOX_DOUBLE -> Double.longBitsToDouble(r.readVarLong());
            case BOX_STRING -> r.readString();
            default -> throw new IllegalArgumentException("unknown boxed tag: " + tag);
        };
    }

    // --- SourceResolutionHints ---

    private static void writeHints(Writer w, SourceResolutionHints hints) {
        if (hints == null) {
            w.writeString("");
            w.writeVarInt(0);
            w.writeVarInt(0);
            w.writeVarInt(0);
            return;
        }
        w.writeString(hints.sourcePackage());
        Map<String, String> singles = hints.singleTypeImports();
        w.writeVarInt(singles.size());
        for (Map.Entry<String, String> e : singles.entrySet()) {
            w.writeString(e.getKey());
            w.writeString(e.getValue());
        }
        writeStringArray(w, hints.onDemandImports());
        Set<String> siblings = hints.siblingSimpleNames();
        w.writeVarInt(siblings.size());
        for (String s : siblings) w.writeString(s);
    }

    private static SourceResolutionHints readHints(Reader r) {
        String sourcePackage = r.readString();
        int singleCount = r.readVarInt();
        Map<String, String> singles;
        if (singleCount == 0) {
            singles = Map.of();
        } else {
            singles = new LinkedHashMap<>(singleCount);
            for (int i = 0; i < singleCount; i++) {
                singles.put(r.readString(), r.readString());
            }
        }
        String[] onDemand = readStringArray(r);
        int siblingCount = r.readVarInt();
        Set<String> siblings;
        if (siblingCount == 0) {
            siblings = Set.of();
        } else {
            siblings = new LinkedHashSet<>(siblingCount);
            for (int i = 0; i < siblingCount; i++) siblings.add(r.readString());
        }
        return new SourceResolutionHints(sourcePackage, singles, onDemand, siblings);
    }

    private static void writeStringArray(Writer w, String[] strings) {
        if (strings == null || strings.length == 0) {
            w.writeVarInt(0);
            return;
        }
        w.writeVarInt(strings.length);
        for (String s : strings) w.writeString(s);
    }

    private static String[] readStringArray(Reader r) {
        int n = r.readVarInt();
        if (n == 0) return EmptyArrays.STRING;
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = r.readString();
        return out;
    }

    // --- Wire helpers ---

    /** Little-endian varint writer backed by a growable byte buffer. */
    private static final class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

        void writeByte(byte b) {
            buf.write(b);
        }

        void writeVarInt(int value) {
            // ZigZag so negatives stay small.
            writeUnsignedVarInt((value << 1) ^ (value >> 31));
        }

        void writeVarLong(long value) {
            writeUnsignedVarLong((value << 1) ^ (value >> 63));
        }

        void writeString(String s) {
            writeUnsignedVarInt(StringTable.intern(s));
        }

        private void writeUnsignedVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                buf.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf.write(value);
        }

        private void writeUnsignedVarLong(long value) {
            while ((value & ~0x7FL) != 0) {
                buf.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            buf.write((int) value);
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        byte readByte() {
            if (pos >= data.length) throw new IllegalArgumentException("truncated blob");
            return data[pos++];
        }

        int readVarInt() {
            int raw = readUnsignedVarInt();
            return (raw >>> 1) ^ -(raw & 1);
        }

        long readVarLong() {
            long raw = readUnsignedVarLong();
            return (raw >>> 1) ^ -(raw & 1);
        }

        String readString() {
            return StringTable.get(readUnsignedVarInt());
        }

        private int readUnsignedVarInt() {
            int result = 0;
            int shift = 0;
            while (true) {
                byte b = readByte();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 35) throw new IllegalArgumentException("malformed varint");
            }
        }

        private long readUnsignedVarLong() {
            long result = 0;
            int shift = 0;
            while (true) {
                byte b = readByte();
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
                if (shift > 70) throw new IllegalArgumentException("malformed varlong");
            }
        }
    }
}
