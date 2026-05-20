package ch.castleridge.javals.indexing.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.intern.Interner;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.Descriptors;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SignatureRefs;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Reads a single {@code .class} file with ASM and appends a {@link TypeEntry}
 * plus its field/method entries into the supplied {@link Index}.
 *
 * <p>Code, debug info and stack map frames are skipped because the index only
 * needs declaration shape and annotations.
 *
 * <p>Every {@link TypeRef} produced by this indexer is already resolved
 * (bytecode always carries fully-qualified JVM names) so the emitted
 * {@link TypeEntry} carries no {@link ch.castleridge.javals.indexing.model.SourceResolutionHints}.
 */
public final class ClassFileIndexer {

    private static final int ASM_API = Opcodes.ASM9;
    private static final int PARSING_OPTIONS = ClassReader.SKIP_CODE
            | ClassReader.SKIP_DEBUG
            | ClassReader.SKIP_FRAMES;

    private ClassFileIndexer() {}

    public static void index(URI uri, URI sourceUri, InputStream in, Index into) throws IOException {
        byte[] bytes = in.readAllBytes();
        index(uri, sourceUri, bytes, into);
    }

    public static void index(URI uri, URI sourceUri, byte[] bytes, Index into) {
        ClassReader reader = new ClassReader(bytes);
        CollectingVisitor visitor = new CollectingVisitor(uri, sourceUri);
        reader.accept(visitor, PARSING_OPTIONS);
        TypeEntry entry = visitor.toTypeEntry();
        if (entry != null) {
            into.add(entry);
        }
    }

    private static final class CollectingVisitor extends ClassVisitor {
        private final String uri;
        private final String sourceUri;

        private String jvmName;
        private int access;
        private TypeRef superRef;
        private List<TypeRef> interfaces = List.of();
        private List<TypeParamRef> typeParams = List.of();
        private final List<FieldEntry> fields = new ArrayList<>();
        private final List<MethodEntry> methods = new ArrayList<>();
        private final List<String> innerTypes = new ArrayList<>();
        private final List<AnnotationRef> annotations = new ArrayList<>();

        CollectingVisitor(URI uri, URI sourceUri) {
            super(ASM_API);
            // resourceUri is unique per class (many methods/fields share it
            // via the single String reference stored on the visitor).
            // sourceUri is shared across every entry in the same classpath
            // input, so we intern it once to collapse the hundreds of
            // thousands of duplicate copies.
            this.uri = uri == null ? null : uri.toString();
            this.sourceUri = sourceUri == null ? null : Interner.intern(sourceUri.toString());
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.jvmName = Interner.intern(name);
            this.access = access;
            if (superName == null) {
                this.superRef = null;
            } else {
                this.superRef = TypeRef.resolved(superName);
            }
            if (interfaces == null || interfaces.length == 0) {
                this.interfaces = List.of();
            } else {
                List<TypeRef> refs = new ArrayList<>(interfaces.length);
                for (String i : interfaces) refs.add(TypeRef.resolved(i));
                this.interfaces = List.copyOf(refs);
            }
            if (signature != null) {
                this.typeParams = SignatureRefs.parseFormalTypeParameters(signature);
                SignatureRefs.ClassRefs classRefs = SignatureRefs.parseClass(signature);
                if (classRefs != null) {
                    if (classRefs.superClass() != null) {
                        this.superRef = classRefs.superClass();
                    }
                    if (!classRefs.interfaces().isEmpty()) {
                        this.interfaces = classRefs.interfaces();
                    }
                }
            } else {
                this.typeParams = List.of();
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return CapturingAnnotationVisitor.forDeclaration(descriptor, annotations::add);
        }

        @Override
        public FieldVisitor visitField(int fAccess, String name, String descriptor,
                                       String fSignature, Object value) {
            final List<AnnotationRef> fAnnotations = new ArrayList<>();
            TypeRef parsedFieldType = fSignature != null
                    ? SignatureRefs.parseType(fSignature)
                    : Descriptors.parseField(descriptor);
            if (parsedFieldType == null) {
                parsedFieldType = Descriptors.parseField(descriptor);
            }
            final TypeRef fieldType = parsedFieldType;
            final String fieldName = Interner.intern(name);
            // Defer FieldEntry construction until visitEnd so the
            // annotations list captured by the visitor below is
            // complete - MethodEntry / FieldEntry copy the list
            // immutably at construction time.
            return new FieldVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return CapturingAnnotationVisitor.forDeclaration(d, fAnnotations::add);
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldEntry(
                            uri,
                            jvmName,
                            fAccess,
                            fieldName,
                            fieldType,
                            fAnnotations));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int mAccess, String name, String descriptor,
                                         String mSignature, String[] exceptions) {
            final List<AnnotationRef> mAnnotations = new ArrayList<>();
            Descriptors.MethodRefs parts = Descriptors.parseMethod(descriptor);
            SignatureRefs.MethodRefs generic = mSignature == null
                    ? null
                    : SignatureRefs.parseMethod(mSignature);
            final TypeRef returnType = generic != null && generic.returnType() != null
                    ? generic.returnType()
                    : parts.returnType();
            final List<TypeRef> paramTypes = generic != null && !generic.paramTypes().isEmpty()
                    ? generic.paramTypes()
                    : parts.paramTypes();
            final List<TypeParamRef> methodTypeParams = generic == null
                    ? List.of()
                    : generic.typeParams();
            final List<TypeRef> throwsRefs;
            if (exceptions == null || exceptions.length == 0) {
                throwsRefs = List.of();
            } else {
                List<TypeRef> ts = new ArrayList<>(exceptions.length);
                for (String e : exceptions) ts.add(TypeRef.resolved(e));
                throwsRefs = List.copyOf(ts);
            }
            final boolean varargs = (mAccess & Opcodes.ACC_VARARGS) != 0;
            final boolean hasBody = (mAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            final String methodName = Interner.intern(name);
            // Box so the inner visitor can update the annotation default
            // value asynchronously before visitEnd builds the MethodEntry.
            final AnnotationValue[] annotationDefaultSlot = new AnnotationValue[1];
            return new MethodVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return CapturingAnnotationVisitor.forDeclaration(d, mAnnotations::add);
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    // AnnotationDefault's value is delivered as a single
                    // unnamed sub-value (visit(null, ...), visitArray(null),
                    // etc.) before visitEnd. Capture it through the same
                    // value-collecting visitor and stash it for visitEnd
                    // to attach to the assembled MethodEntry.
                    return CapturingAnnotationVisitor.forValue(value -> annotationDefaultSlot[0] = value);
                }

                @Override
                public void visitEnd() {
                    methods.add(new MethodEntry(
                            uri,
                            jvmName,
                            mAccess,
                            methodName,
                            returnType,
                            paramTypes,
                            throwsRefs,
                            methodTypeParams,
                            varargs,
                            hasBody,
                            annotationDefaultSlot[0],
                            mAnnotations));
                }
            };
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (outerName != null && outerName.equals(jvmName)) {
                innerTypes.add(Interner.intern(name));
            }
        }

        TypeEntry toTypeEntry() {
            if (jvmName == null) return null;
            if (Index.isSkippedJvmName(jvmName)) return null;
            return new TypeEntry(
                    uri,
                    sourceUri,
                    jvmName,
                    access,
                    superRef,
                    interfaces,
                    typeParams,
                    fields,
                    methods,
                    innerTypes,
                    annotations,
                    null);
        }
    }

    /**
     * ASM annotation visitor that materialises every visited sub-value into
     * an {@link AnnotationValue} and delivers the final result to a sink
     * callback at {@link #visitEnd()}.
     *
     * <p>Three modes:
     * <ul>
     *   <li>{@link Mode#DECLARATION}: top-level annotation on a class /
     *       field / method. The collected value map is wrapped in an
     *       {@link AnnotationRef} and emitted to the declaration sink.
     *   <li>{@link Mode#NESTED}: a nested annotation inside another
     *       annotation's element map. Same as DECLARATION but the
     *       containing visitor stores the resulting {@link AnnotationRef}
     *       wrapped in {@link AnnotationValue.Nested}.
     *   <li>{@link Mode#ARRAY}: inside an ASM {@code visitArray} callback;
     *       sub-values come in via the unnamed {@code visit}/{@code visitEnum}
     *       /{@code visitAnnotation}/{@code visitArray} calls and are
     *       pushed onto an ordered list, then handed to the caller as an
     *       {@link AnnotationValue.Arr}.
     *   <li>{@link Mode#SINGLE_VALUE}: AnnotationDefault's single unnamed
     *       sub-value. Captures exactly one value via the unnamed hooks.
     * </ul>
     */
    private static final class CapturingAnnotationVisitor extends AnnotationVisitor {

        private enum Mode { DECLARATION, NESTED, ARRAY, SINGLE_VALUE }

        private final Mode mode;
        private final String descriptor;
        private final Map<String, AnnotationValue> values;
        private final List<AnnotationValue> arrayElements;
        private final Consumer<AnnotationRef> annotationSink;
        private final Consumer<AnnotationValue> valueSink;

        private CapturingAnnotationVisitor(Mode mode,
                                           String descriptor,
                                           Consumer<AnnotationRef> annotationSink,
                                           Consumer<AnnotationValue> valueSink) {
            super(ASM_API);
            this.mode = mode;
            this.descriptor = descriptor;
            this.annotationSink = annotationSink;
            this.valueSink = valueSink;
            this.values = (mode == Mode.DECLARATION || mode == Mode.NESTED) ? new HashMap<>() : null;
            this.arrayElements = mode == Mode.ARRAY ? new ArrayList<>() : null;
        }

        static CapturingAnnotationVisitor forDeclaration(String descriptor, Consumer<AnnotationRef> sink) {
            return new CapturingAnnotationVisitor(Mode.DECLARATION, descriptor, sink, null);
        }

        static CapturingAnnotationVisitor forNested(String descriptor, Consumer<AnnotationValue> sink) {
            return new CapturingAnnotationVisitor(Mode.NESTED, descriptor, null, sink);
        }

        static CapturingAnnotationVisitor forArray(Consumer<AnnotationValue> sink) {
            return new CapturingAnnotationVisitor(Mode.ARRAY, null, null, sink);
        }

        static CapturingAnnotationVisitor forValue(Consumer<AnnotationValue> sink) {
            return new CapturingAnnotationVisitor(Mode.SINGLE_VALUE, null, null, sink);
        }

        @Override
        public void visit(String name, Object value) {
            AnnotationValue v = primitiveOrStringOrClassOrArray(value);
            store(name, v);
        }

        @Override
        public void visitEnum(String name, String enumDescriptor, String value) {
            TypeRef enumType = Descriptors.parseField(enumDescriptor);
            store(name, new AnnotationValue.EnumConst(enumType, Interner.intern(value)));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
            return CapturingAnnotationVisitor.forNested(nestedDescriptor,
                    nested -> store(name, nested));
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return CapturingAnnotationVisitor.forArray(arr -> store(name, arr));
        }

        @Override
        public void visitEnd() {
            switch (mode) {
                case DECLARATION -> annotationSink.accept(new AnnotationRef(jvmNameFor(descriptor), values));
                case NESTED -> valueSink.accept(new AnnotationValue.Nested(
                        new AnnotationRef(jvmNameFor(descriptor), values)));
                case ARRAY -> valueSink.accept(new AnnotationValue.Arr(arrayElements));
                case SINGLE_VALUE -> {
                    // AnnotationDefault may have zero or one captured value.
                    // If zero, there's nothing to deliver and the entry
                    // keeps its previous default (null in our case).
                    // The single-value case is delivered via store(null, v)
                    // below, which already forwarded to the sink.
                }
            }
        }

        private void store(String name, AnnotationValue value) {
            switch (mode) {
                case DECLARATION, NESTED -> {
                    // Top-level: each element has a non-null name.
                    if (name != null) {
                        values.put(Interner.intern(name), value);
                    }
                }
                case ARRAY -> arrayElements.add(value);
                case SINGLE_VALUE -> valueSink.accept(value);
            }
        }

        private static String jvmNameFor(String descriptor) {
            TypeRef ref = Descriptors.parseField(descriptor);
            if (ref instanceof TypeRef.Resolved r) return r.jvmBinaryName();
            return Interner.intern(descriptor);
        }

        /**
         * Convert the {@code value} delivered by ASM's
         * {@link AnnotationVisitor#visit(String, Object)} into an
         * {@link AnnotationValue}. ASM uses this single hook for boxed
         * primitives, {@link String}, {@link org.objectweb.asm.Type} (for
         * class literals) and pre-packed primitive arrays
         * ({@code byte[]}, {@code int[]}, ...).
         */
        private static AnnotationValue primitiveOrStringOrClassOrArray(Object value) {
            if (value == null) {
                return new AnnotationValue.Unsupported("null literal");
            }
            if (value instanceof String s) {
                return new AnnotationValue.Str(s);
            }
            if (value instanceof org.objectweb.asm.Type asmType) {
                return new AnnotationValue.ClassRef(asmTypeToRef(asmType));
            }
            if (value instanceof Boolean
                    || value instanceof Byte
                    || value instanceof Short
                    || value instanceof Character
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double) {
                return new AnnotationValue.Primitive(value);
            }
            if (value.getClass().isArray()) {
                List<AnnotationValue> elements = new ArrayList<>();
                int n = java.lang.reflect.Array.getLength(value);
                for (int i = 0; i < n; i++) {
                    Object e = java.lang.reflect.Array.get(value, i);
                    elements.add(primitiveOrStringOrClassOrArray(e));
                }
                return new AnnotationValue.Arr(elements);
            }
            return new AnnotationValue.Unsupported("unexpected literal: " + value.getClass().getName());
        }

        private static TypeRef asmTypeToRef(org.objectweb.asm.Type asmType) {
            int sort = asmType.getSort();
            switch (sort) {
                case org.objectweb.asm.Type.VOID: return TypeRef.Primitive.VOID;
                case org.objectweb.asm.Type.BOOLEAN: return TypeRef.Primitive.BOOLEAN;
                case org.objectweb.asm.Type.BYTE: return TypeRef.Primitive.BYTE;
                case org.objectweb.asm.Type.CHAR: return TypeRef.Primitive.CHAR;
                case org.objectweb.asm.Type.SHORT: return TypeRef.Primitive.SHORT;
                case org.objectweb.asm.Type.INT: return TypeRef.Primitive.INT;
                case org.objectweb.asm.Type.LONG: return TypeRef.Primitive.LONG;
                case org.objectweb.asm.Type.FLOAT: return TypeRef.Primitive.FLOAT;
                case org.objectweb.asm.Type.DOUBLE: return TypeRef.Primitive.DOUBLE;
                case org.objectweb.asm.Type.ARRAY: {
                    TypeRef elem = asmTypeToRef(asmType.getElementType());
                    int dims = asmType.getDimensions();
                    TypeRef out = elem;
                    for (int i = 0; i < dims; i++) {
                        out = new TypeRef.Array(out);
                    }
                    return out;
                }
                case org.objectweb.asm.Type.OBJECT: return TypeRef.resolved(asmType.getInternalName());
                default: return TypeRef.resolved("java/lang/Object");
            }
        }
    }
}
