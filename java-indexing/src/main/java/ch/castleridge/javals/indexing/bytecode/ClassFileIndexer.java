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
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.Descriptors;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
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
        private final URI uri;
        private final URI sourceUri;

        private String jvmName;
        private int access;
        private String superName;
        private String signature;
        private List<TypeRef> interfaces = List.of();
        private final List<FieldEntry> fields = new ArrayList<>();
        private final List<MethodEntry> methods = new ArrayList<>();
        private final List<String> innerTypes = new ArrayList<>();
        private final List<AnnotationRef> annotations = new ArrayList<>();

        CollectingVisitor(URI uri, URI sourceUri) {
            super(ASM_API);
            this.uri = uri;
            this.sourceUri = sourceUri;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.jvmName = name;
            this.access = access;
            this.superName = superName;
            this.signature = signature;
            if (interfaces == null || interfaces.length == 0) {
                this.interfaces = List.of();
            } else {
                List<TypeRef> refs = new ArrayList<>(interfaces.length);
                for (String i : interfaces) refs.add(new TypeRef.Resolved(i));
                this.interfaces = List.copyOf(refs);
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
            FieldEntry fe = new FieldEntry(
                    uri,
                    jvmName,
                    fAccess,
                    name,
                    Descriptors.parseField(descriptor),
                    fSignature,
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
            List<TypeRef> throwsRefs;
            if (exceptions == null || exceptions.length == 0) {
                throwsRefs = List.of();
            } else {
                List<TypeRef> ts = new ArrayList<>(exceptions.length);
                for (String e : exceptions) ts.add(new TypeRef.Resolved(e));
                throwsRefs = List.copyOf(ts);
            }
            MethodEntry me = new MethodEntry(
                    uri,
                    jvmName,
                    mAccess,
                    name,
                    parts.returnType(),
                    parts.paramTypes(),
                    throwsRefs,
                    mSignature,
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
                innerTypes.add(name);
            }
        }

        TypeEntry toTypeEntry() {
            if (jvmName == null) return null;
            if (Index.isSkippedJvmName(jvmName)) return null;
            TypeRef superRef = superName == null ? null : new TypeRef.Resolved(superName);
            return new TypeEntry(
                    uri,
                    sourceUri,
                    jvmName,
                    access,
                    signature,
                    superRef,
                    interfaces,
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
            String jvm = ref instanceof TypeRef.Resolved r ? r.jvmBinaryName() : descriptor;
            sink.add(new AnnotationRef(jvm, values));
        }
    }
}
