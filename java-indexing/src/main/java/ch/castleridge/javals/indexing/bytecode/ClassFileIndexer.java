package ch.castleridge.javals.indexing.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.intern.Interner;
import ch.castleridge.javals.indexing.model.AnnotationRef;
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
            return new CapturingAnnotationVisitor(descriptor, annotations);
        }

        @Override
        public FieldVisitor visitField(int fAccess, String name, String descriptor,
                                       String fSignature, Object value) {
            List<AnnotationRef> fAnnotations = new ArrayList<>();
            TypeRef fieldType = fSignature != null
                    ? SignatureRefs.parseType(fSignature)
                    : Descriptors.parseField(descriptor);
            if (fieldType == null) {
                fieldType = Descriptors.parseField(descriptor);
            }
            FieldEntry fe = new FieldEntry(
                    uri,
                    jvmName,
                    fAccess,
                    Interner.intern(name),
                    fieldType,
                    fAnnotations);
            fields.add(fe);
            return new FieldVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return new CapturingAnnotationVisitor(d, fAnnotations);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int mAccess, String name, String descriptor,
                                         String mSignature, String[] exceptions) {
            List<AnnotationRef> mAnnotations = new ArrayList<>();
            Descriptors.MethodRefs parts = Descriptors.parseMethod(descriptor);
            SignatureRefs.MethodRefs generic = mSignature == null
                    ? null
                    : SignatureRefs.parseMethod(mSignature);
            TypeRef returnType = generic != null && generic.returnType() != null
                    ? generic.returnType()
                    : parts.returnType();
            List<TypeRef> paramTypes = generic != null && !generic.paramTypes().isEmpty()
                    ? generic.paramTypes()
                    : parts.paramTypes();
            List<TypeParamRef> methodTypeParams = generic == null
                    ? List.of()
                    : generic.typeParams();
            List<TypeRef> throwsRefs;
            if (exceptions == null || exceptions.length == 0) {
                throwsRefs = List.of();
            } else {
                List<TypeRef> ts = new ArrayList<>(exceptions.length);
                for (String e : exceptions) ts.add(TypeRef.resolved(e));
                throwsRefs = List.copyOf(ts);
            }
            boolean varargs = (mAccess & Opcodes.ACC_VARARGS) != 0;
            boolean hasBody = (mAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
            MethodEntry me = new MethodEntry(
                    uri,
                    jvmName,
                    mAccess,
                    Interner.intern(name),
                    returnType,
                    paramTypes,
                    throwsRefs,
                    methodTypeParams,
                    varargs,
                    hasBody,
                    mAnnotations);
            methods.add(me);
            return new MethodVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return new CapturingAnnotationVisitor(d, mAnnotations);
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

    private static final class CapturingAnnotationVisitor extends AnnotationVisitor {
        private final String descriptor;
        private final List<AnnotationRef> sink;
        private final Map<String, Object> values = new HashMap<>();

        CapturingAnnotationVisitor(String descriptor, List<AnnotationRef> sink) {
            super(ASM_API);
            this.descriptor = descriptor;
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            if (name != null) values.put(name, value);
        }

        @Override
        public void visitEnum(String name, String enumDescriptor, String value) {
            if (name != null) values.put(name, enumDescriptor + ":" + value);
        }

        @Override
        public void visitEnd() {
            TypeRef ref = Descriptors.parseField(descriptor);
            String jvm = ref instanceof TypeRef.Resolved r ? r.jvmBinaryName() : Interner.intern(descriptor);
            sink.add(new AnnotationRef(jvm, values));
        }
    }
}
