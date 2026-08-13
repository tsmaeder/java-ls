package ch.castleridge.javals.indexing.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.AccessVisibility;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.Descriptors;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ModuleEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.ResourceUris;
import ch.castleridge.javals.indexing.model.SignatureRefs;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Reads a single {@code .class} file with ASM and appends a {@link TypeEntry}
 * plus its field/method entries into the supplied {@link Index}.
 *
 * <p>Only members that might be visible from another compilation unit are
 * stored: private fields and methods are skipped (private {@code <init>}
 * constructors are kept), and private nested types are omitted entirely.
 *
 * <p>Code, debug info and stack map frames are skipped because the index only
 * needs declaration shape and annotations.
 *
 * <p>Every class-type {@link TypeRef} produced by this indexer is already resolved
 * (bytecode always carries fully-qualified JVM names) so the emitted
 * {@link TypeEntry} carries no {@link ch.castleridge.javals.indexing.model.SourceResolutionHints}.
 */
public final class ClassFileIndexer {

    private static final int ASM_API = Opcodes.ASM9;
    // Bits carried by InnerClasses entries that materially affect
    // nested-member symbol shape (including ACC_STATIC for member
    // interfaces/classes like java.util.Map$Entry).
    private static final int INNER_CLASS_ACCESS_MASK =
            Opcodes.ACC_PUBLIC
                    | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_PROTECTED
                    | Opcodes.ACC_STATIC
                    | Opcodes.ACC_FINAL
                    | Opcodes.ACC_INTERFACE
                    | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_SYNTHETIC
                    | Opcodes.ACC_ANNOTATION
                    | Opcodes.ACC_ENUM;
    // ClassReader.SKIP_DEBUG would discard the SourceFile, LVT *and*
    // MethodParameters attributes; we want MethodParameters to flow
    // through so MethodEntry can carry parameter names. Method bodies
    // (where LVT lives) are still skipped via SKIP_CODE, so the extra
    // attributes parsed here are only the small per-class/per-method
    // metadata blobs.
    private static final int PARSING_OPTIONS = ClassReader.SKIP_CODE
            | ClassReader.SKIP_FRAMES;

    private ClassFileIndexer() {}

    public static void index(String resourcePath, String sourceUri, InputStream in, Index into) throws IOException {
        byte[] bytes = in.readAllBytes();
        index(resourcePath, sourceUri, bytes, into);
    }

    public static void index(String resourcePath, String sourceUri, byte[] bytes, Index into) {
        ClassReader reader = new ClassReader(bytes);
        CollectingVisitor visitor = new CollectingVisitor(resourcePath, sourceUri);
        reader.accept(visitor, PARSING_OPTIONS);
        ModuleEntry module = visitor.toModuleEntry();
        if (module != null) {
            // A module-info.class never has any useful TypeEntry payload
            // (no fields, no methods, no superclass beyond Object), so
            // route it to the module store and stop here.
            into.addModule(module);
            return;
        }
        TypeEntry entry = visitor.toTypeEntry();
        if (entry != null) {
            into.add(entry);
            String resourceUri = ResourceUris.resolve(sourceUri, resourcePath);
            if (resourceUri != null) {
                into.registerBloom(resourceUri, IdentifierBloomFilter.create(
                        simpleNamesFromConstantPool(reader)));
            }
        }
    }

    /**
     * Collect simple names of every {@code CONSTANT_Class} entry in the
     * classfile constant pool. Covers superclass, interfaces, descriptor
     * types, and types referenced from code (the pool is complete even when
     * method bodies are skipped via {@link ClassReader#SKIP_CODE}).
     */
    static Set<String> simpleNamesFromConstantPool(ClassReader reader) {
        Set<String> names = new HashSet<>();
        char[] buf = new char[reader.getMaxStringLength()];
        int itemCount = reader.getItemCount();
        for (int i = 1; i < itemCount; i++) {
            int itemOffset = reader.getItem(i);
            int tag = reader.readByte(itemOffset - 1);
            // Long / Double occupy two constant-pool slots.
            if (tag == 5 || tag == 6) {
                i++;
                continue;
            }
            if (tag != 7) continue; // CONSTANT_Class
            String internal = reader.readUTF8(itemOffset, buf);
            if (internal == null || internal.isEmpty() || internal.charAt(0) == '[') continue;
            int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
            names.add(cut < 0 ? internal : internal.substring(cut + 1));
        }
        return names;
    }

    private static final class CollectingVisitor extends ClassVisitor {
        private final String resourcePath;
        private final String sourceUri;

        private String jvmName;
        private int access;
        private Type superRef;
        private List<Type> interfaces = List.of();
        private List<TypeParamRef> typeParams = List.of();
        private final List<FieldEntry> fields = new ArrayList<>();
        private final List<MethodEntry> methods = new ArrayList<>();
        private final List<String> innerTypes = new ArrayList<>();
        private final List<TypeRef> permittedSubclasses = new ArrayList<>();
        private final List<RecordComponentEntry> recordComponents = new ArrayList<>();
        private final List<AnnotationRef> annotations = new ArrayList<>();

        // Populated when this is a module-info class file. Only one of
        // toTypeEntry / toModuleEntry yields a non-null result.
        private ModuleEntry moduleEntry;
        private String mainClass;
        private final List<String> modulePackages = new ArrayList<>();

        CollectingVisitor(String resourcePath, String sourceUri) {
            super(ASM_API);
            // resourcePath may be a relative path or (for synthetic test URIs)
            // an absolute URI; ResourceUris.compact normalises storage when
            // the TypeEntry / ModuleEntry is built.
            this.resourcePath = resourcePath;
            this.sourceUri = sourceUri;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.jvmName = name;
            this.access = access;
            if (superName == null) {
                this.superRef = null;
            } else {
                this.superRef = TypeRef.resolved(superName);
            }
            if (interfaces == null || interfaces.length == 0) {
                this.interfaces = List.of();
            } else {
                List<Type> refs = new ArrayList<>(interfaces.length);
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
            if (!AccessVisibility.shouldIndexMember(fAccess, name)) {
                return null;
            }
            final List<AnnotationRef> fAnnotations = new ArrayList<>();
            Type parsedFieldType = fSignature != null
                    ? SignatureRefs.parseType(fSignature)
                    : Descriptors.parseField(descriptor);
            if (parsedFieldType == null) {
                parsedFieldType = Descriptors.parseField(descriptor);
            }
            final Type[] fieldTypeSlot = new Type[]{parsedFieldType};
            final String fieldName = name;
            // ASM hands us the ConstantValue attribute's payload directly:
            // boxed Integer / Long / Float / Double for primitives, String
            // for string constants, or null when there is no ConstantValue.
            // Pass it through unchanged so IndexClassReader can call
            // VarSymbol.setData() and let javac constant-fold use sites.
            final Object constantValue = value;
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
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                             String d, boolean visible) {
                    // Only handle the top-level position (typePath == null);
                    // deeper paths inside generic structure are dropped on
                    // the first cut.
                    if (typePath != null) return null;
                    return CapturingAnnotationVisitor.forDeclaration(d, ann ->
                            fieldTypeSlot[0] = Type.Annotated.wrap(fieldTypeSlot[0], new AnnotationRef[]{ann}));
                }

                @Override
                public void visitEnd() {
                    fields.add(new FieldEntry(
                            fAccess,
                            fieldName,
                            fieldTypeSlot[0],
                            constantValue,
                            EmptyArrays.toArray(fAnnotations, EmptyArrays.ANNOTATION_REF)));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int mAccess, String name, String descriptor,
                                         String mSignature, String[] exceptions) {
            if (!AccessVisibility.shouldIndexMember(mAccess, name)) {
                return null;
            }
            final List<AnnotationRef> mAnnotations = new ArrayList<>();
            Descriptors.MethodRefs parts = Descriptors.parseMethod(descriptor);
            SignatureRefs.MethodRefs generic = mSignature == null
                    ? null
                    : SignatureRefs.parseMethod(mSignature);
            final Type returnType = generic != null && generic.returnType() != null
                    ? generic.returnType()
                    : parts.returnType();
            final List<Type> paramTypes = generic != null && !generic.paramTypes().isEmpty()
                    ? generic.paramTypes()
                    : parts.paramTypes();
            final List<TypeParamRef> methodTypeParams = generic == null
                    ? List.of()
                    : generic.typeParams();
            final List<Type> throwsRefs;
            if (generic != null && !generic.throwsTypes().isEmpty()) {
                throwsRefs = generic.throwsTypes();
            } else if (exceptions == null || exceptions.length == 0) {
                throwsRefs = List.of();
            } else {
                List<Type> ts = new ArrayList<>(exceptions.length);
                for (String e : exceptions) ts.add(TypeRef.resolved(e));
                throwsRefs = List.copyOf(ts);
            }
            final boolean varargs = (mAccess & Opcodes.ACC_VARARGS) != 0;
            final boolean hasBody = (mAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            final String methodName = name;
            // Box so the inner visitor can update the annotation default
            // value asynchronously before visitEnd builds the MethodEntry.
            final AnnotationValue[] annotationDefaultSlot = new AnnotationValue[1];
            // MethodParameters and Runtime{Visible,Invisible}ParameterAnnotations
            // arrive through visitParameter / visitParameterAnnotation in
            // declaration order, but their indices line up with paramTypes
            // (signature-style parameter slots, not LVT slots) only after
            // ASM has cancelled out implicit synthetic parameters.
            // We capture them per parameter and zip with paramTypes at
            // visitEnd; if MethodParameters is absent we leave the name
            // null and IndexClassReader synthesises "arg<i>".
            final String[] parameterNames = new String[paramTypes.size()];
            final int[] parameterModifiers = new int[paramTypes.size()];
            final List<AnnotationRef>[] parameterAnnotations = newAnnotationLists(paramTypes.size());
            final int[] parameterCursor = new int[]{0};
            // Mutable slots so visitTypeAnnotation can wrap the relevant
            // Type in Type.Annotated decorators as type-use
            // annotations arrive. Only the top-level position
            // (typePath == null) is handled in this first cut.
            final Type[] returnTypeSlot = new Type[]{returnType};
            final Type[] paramTypeSlots = paramTypes.toArray(new Type[0]);
            final Type[] throwsSlots = throwsRefs.toArray(new Type[0]);
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
                public void visitParameter(String pName, int pAccess) {
                    int idx = parameterCursor[0]++;
                    if (idx >= 0 && idx < parameterNames.length) {
                        parameterNames[idx] = pName;
                        parameterModifiers[idx] = pAccess;
                    }
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                    if (parameter < 0 || parameter >= parameterAnnotations.length) {
                        return null;
                    }
                    return CapturingAnnotationVisitor.forDeclaration(d, parameterAnnotations[parameter]::add);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                             String d, boolean visible) {
                    if (typePath != null) return null;
                    int sort = new TypeReference(typeRef).getSort();
                    return switch (sort) {
                        case TypeReference.METHOD_RETURN -> CapturingAnnotationVisitor.forDeclaration(d, ann ->
                                returnTypeSlot[0] = Type.Annotated.wrap(returnTypeSlot[0], new AnnotationRef[]{ann}));
                        case TypeReference.METHOD_FORMAL_PARAMETER -> {
                            int idx = new TypeReference(typeRef).getFormalParameterIndex();
                            if (idx < 0 || idx >= paramTypeSlots.length) yield null;
                            yield CapturingAnnotationVisitor.forDeclaration(d, ann ->
                                    paramTypeSlots[idx] = Type.Annotated.wrap(paramTypeSlots[idx], new AnnotationRef[]{ann}));
                        }
                        case TypeReference.THROWS -> {
                            int idx = new TypeReference(typeRef).getExceptionIndex();
                            if (idx < 0 || idx >= throwsSlots.length) yield null;
                            yield CapturingAnnotationVisitor.forDeclaration(d, ann ->
                                    throwsSlots[idx] = Type.Annotated.wrap(throwsSlots[idx], new AnnotationRef[]{ann}));
                        }
                        default -> null;
                    };
                }

                @Override
                public void visitEnd() {
                    ParameterEntry[] params = new ParameterEntry[paramTypes.size()];
                    for (int i = 0; i < paramTypes.size(); i++) {
                        params[i] = new ParameterEntry(
                                parameterNames[i],
                                parameterModifiers[i],
                                paramTypeSlots[i],
                                EmptyArrays.toArray(parameterAnnotations[i], EmptyArrays.ANNOTATION_REF));
                    }
                    methods.add(new MethodEntry(
                            mAccess,
                            methodName,
                            returnTypeSlot[0],
                            params,
                            throwsSlots,
                            EmptyArrays.toArray(methodTypeParams, EmptyArrays.TYPE_PARAM),
                            varargs,
                            hasBody,
                            annotationDefaultSlot[0],
                            EmptyArrays.toArray(mAnnotations, EmptyArrays.ANNOTATION_REF)));
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static List<AnnotationRef>[] newAnnotationLists(int n) {
            List<AnnotationRef>[] arr = new List[n];
            for (int i = 0; i < n; i++) arr[i] = new ArrayList<>();
            return arr;
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // For nested/member types, the InnerClasses attribute is the
            // source of truth for ACC_STATIC (and related nested metadata).
            // Merge the self-entry bits into this class's access flags so
            // downstream symbol synthesis can correctly distinguish static
            // members from non-static inners.
            if (name != null && name.equals(jvmName)) {
                this.access |= (access & INNER_CLASS_ACCESS_MASK);
            }
            if (outerName != null && outerName.equals(jvmName)
                    && AccessVisibility.shouldIndexType(access)) {
                innerTypes.add(name);
            }
        }

        @Override
        public ModuleVisitor visitModule(String name, int moduleAccess, String version) {
            final String modName = name;
            final String modVersion = version;
            final int modFlags = moduleAccess;
            final List<ModuleEntry.Requires> requires = new ArrayList<>();
            final List<ModuleEntry.Exports> exports = new ArrayList<>();
            final List<ModuleEntry.Opens> opens = new ArrayList<>();
            final List<String> uses = new ArrayList<>();
            final List<ModuleEntry.Provides> provides = new ArrayList<>();
            return new ModuleVisitor(ASM_API) {
                @Override
                public void visitMainClass(String main) {
                    if (main != null && !main.isEmpty()) {
                        mainClass = main;
                    }
                }

                @Override
                public void visitPackage(String packaze) {
                    if (packaze != null && !packaze.isEmpty()) {
                        modulePackages.add(packaze);
                    }
                }

                @Override
                public void visitRequire(String reqModule, int access, String reqVersion) {
                    if (reqModule == null || reqModule.isEmpty()) return;
                    requires.add(new ModuleEntry.Requires(
                            reqModule,
                            access,
                            reqVersion));
                }

                @Override
                public void visitExport(String packaze, int access, String... modules) {
                    if (packaze == null) return;
                    exports.add(new ModuleEntry.Exports(
                            packaze,
                            compactArray(modules),
                            access));
                }

                @Override
                public void visitOpen(String packaze, int access, String... modules) {
                    if (packaze == null) return;
                    opens.add(new ModuleEntry.Opens(
                            packaze,
                            compactArray(modules),
                            access));
                }

                @Override
                public void visitUse(String service) {
                    if (service == null) return;
                    uses.add(service);
                }

                @Override
                public void visitProvide(String service, String... providers) {
                    if (service == null) return;
                    provides.add(new ModuleEntry.Provides(
                            service,
                            compactArray(providers)));
                }

                @Override
                public void visitEnd() {
                    moduleEntry = new ModuleEntry(
                            resourcePath,
                            sourceUri,
                            modName,
                            modVersion,
                            modFlags,
                            EmptyArrays.toArray(requires, EmptyArrays.REQUIRES),
                            EmptyArrays.toArray(exports, EmptyArrays.EXPORTS),
                            EmptyArrays.toArray(opens, EmptyArrays.OPENS),
                            EmptyArrays.toArray(uses, EmptyArrays.STRING),
                            EmptyArrays.toArray(provides, EmptyArrays.PROVIDES),
                            EmptyArrays.toArray(modulePackages, EmptyArrays.STRING),
                            mainClass);
                }
            };
        }

        private static String[] compactArray(String[] arr) {
            if (arr == null || arr.length == 0) return EmptyArrays.STRING;
            String[] out = new String[arr.length];
            int n = 0;
            for (String s : arr) {
                if (s != null) out[n++] = s;
            }
            if (n == 0) return EmptyArrays.STRING;
            if (n == out.length) return out;
            return java.util.Arrays.copyOf(out, n);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            if (permittedSubclass != null && !permittedSubclass.isEmpty()) {
                permittedSubclasses.add(TypeRef.resolved(permittedSubclass));
            }
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                                                           String signature) {
            Type componentType = signature != null
                    ? SignatureRefs.parseType(signature)
                    : Descriptors.parseField(descriptor);
            if (componentType == null) {
                componentType = Descriptors.parseField(descriptor);
            }
            final String componentName = name;
            final Type finalComponentType = componentType;
            final List<AnnotationRef> componentAnnotations = new ArrayList<>();
            return new RecordComponentVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return CapturingAnnotationVisitor.forDeclaration(d, componentAnnotations::add);
                }

                @Override
                public void visitEnd() {
                    recordComponents.add(new RecordComponentEntry(
                            componentName, finalComponentType,
                            EmptyArrays.toArray(componentAnnotations, EmptyArrays.ANNOTATION_REF)));
                }
            };
        }

        ModuleEntry toModuleEntry() {
            return moduleEntry;
        }

        TypeEntry toTypeEntry() {
            if (jvmName == null) return null;
            // Module-info pseudo-classes round-trip through toModuleEntry();
            // their JVM owner name is "module-info" which we want to keep
            // off the type index altogether.
            if (moduleEntry != null) return null;
            if (Index.isSkippedJvmName(jvmName)) return null;
            // Private nested types are never visible from another CU.
            if (!AccessVisibility.shouldIndexType(access)) return null;
            return new ClassFileTypeEntry(
                    resourcePath,
                    sourceUri,
                    jvmName,
                    access,
                    superRef,
                    EmptyArrays.toArray(interfaces, EmptyArrays.TYPE),
                    EmptyArrays.toArray(typeParams, EmptyArrays.TYPE_PARAM),
                    EmptyArrays.toArray(fields, EmptyArrays.FIELD),
                    EmptyArrays.toArray(methods, EmptyArrays.METHOD),
                    EmptyArrays.toArray(innerTypes, EmptyArrays.STRING),
                    EmptyArrays.toArray(permittedSubclasses, EmptyArrays.TYPE_REF),
                    EmptyArrays.toArray(recordComponents, EmptyArrays.RECORD_COMPONENT),
                    EmptyArrays.toArray(annotations, EmptyArrays.ANNOTATION_REF));
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
            Type enumType = Descriptors.parseField(enumDescriptor);
            store(name, new AnnotationValue.EnumConst(enumType, value));
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
                case DECLARATION -> annotationSink.accept(
                        new AnnotationRef(TypeRef.resolved(jvmNameFor(descriptor)), values));
                case NESTED -> valueSink.accept(new AnnotationValue.Nested(
                        new AnnotationRef(TypeRef.resolved(jvmNameFor(descriptor)), values)));
                case ARRAY -> valueSink.accept(new AnnotationValue.Arr(
                        EmptyArrays.toArray(arrayElements, EmptyArrays.ANNOTATION_VALUE)));
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
                        values.put(name, value);
                    }
                }
                case ARRAY -> arrayElements.add(value);
                case SINGLE_VALUE -> valueSink.accept(value);
            }
        }

        private static String jvmNameFor(String descriptor) {
            Type ref = Descriptors.parseField(descriptor);
            if (ref instanceof TypeRef.Resolved r) return r.jvmBinaryName();
            return descriptor;
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
                int n = java.lang.reflect.Array.getLength(value);
                if (n == 0) return new AnnotationValue.Arr(EmptyArrays.ANNOTATION_VALUE);
                AnnotationValue[] elements = new AnnotationValue[n];
                for (int i = 0; i < n; i++) {
                    Object e = java.lang.reflect.Array.get(value, i);
                    elements[i] = primitiveOrStringOrClassOrArray(e);
                }
                return new AnnotationValue.Arr(elements);
            }
            return new AnnotationValue.Unsupported("unexpected literal: " + value.getClass().getName());
        }

        private static Type asmTypeToRef(org.objectweb.asm.Type asmType) {
            int sort = asmType.getSort();
            switch (sort) {
                case org.objectweb.asm.Type.VOID: return Type.Primitive.VOID;
                case org.objectweb.asm.Type.BOOLEAN: return Type.Primitive.BOOLEAN;
                case org.objectweb.asm.Type.BYTE: return Type.Primitive.BYTE;
                case org.objectweb.asm.Type.CHAR: return Type.Primitive.CHAR;
                case org.objectweb.asm.Type.SHORT: return Type.Primitive.SHORT;
                case org.objectweb.asm.Type.INT: return Type.Primitive.INT;
                case org.objectweb.asm.Type.LONG: return Type.Primitive.LONG;
                case org.objectweb.asm.Type.FLOAT: return Type.Primitive.FLOAT;
                case org.objectweb.asm.Type.DOUBLE: return Type.Primitive.DOUBLE;
                case org.objectweb.asm.Type.ARRAY: {
                    Type elem = asmTypeToRef(asmType.getElementType());
                    int dims = asmType.getDimensions();
                    Type out = elem;
                    for (int i = 0; i < dims; i++) {
                        out = Type.array(out);
                    }
                    return out;
                }
                case org.objectweb.asm.Type.OBJECT: return TypeRef.resolved(asmType.getInternalName());
                default: return TypeRef.resolved("java/lang/Object");
            }
        }
    }
}
