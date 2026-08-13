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
package ch.castleridge.javals.analysis.ecj;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation;
import org.eclipse.jdt.internal.compiler.env.IBinaryField;
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod;
import org.eclipse.jdt.internal.compiler.env.IBinaryNestedType;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation;
import org.eclipse.jdt.internal.compiler.env.IRecordComponent;
import org.eclipse.jdt.internal.compiler.env.ITypeAnnotationWalker;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding.ExternalAnnotationStatus;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;

import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * {@link IBinaryType} backed directly by an indexed {@link TypeEntry}.
 * Both {@link SourceTypeEntry} and {@link ClassFileTypeEntry} are exposed
 * this way; unresolved source refs are resolved against the active classpath.
 */
final class IndexBinaryType implements IBinaryType {
    private final char[] name;
    private final char[] sourceName;
    private final char[] fileName;
    private final char[] sourceFileName;
    private final char[] enclosingTypeName;
    private final char[] superclassName;
    private final char[][] interfaceNames;
    private final char[] genericSignature;
    private final char[][] permittedSubtypes;
    private final int modifiers;
    private final long tagBits;
    private final boolean record;
    private final boolean member;
    private final IBinaryField[] fields;
    private final IBinaryMethod[] methods;
    private final IBinaryNestedType[] memberTypes;
    private final IRecordComponent[] recordComponents;
    private final IBinaryAnnotation[] annotations;
    private final URI uri;

    static IndexBinaryType of(TypeEntry entry, Index index, ClasspathOrder classpath) {
        return new IndexBinaryType(entry, index, classpath);
    }

    private IndexBinaryType(TypeEntry entry, Index index, ClasspathOrder classpath) {
        ClasspathOrder order = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        IndexTypeEncoding encoding = new IndexTypeEncoding(entry, index, order);
        this.name = entry.jvmOwnerName().toCharArray();
        this.sourceName = sourceName(entry.jvmOwnerName()).toCharArray();
        String resource = entry.resourceUri();
        this.fileName = resource == null || resource.isBlank()
                ? (entry.jvmOwnerName() + ".class").toCharArray()
                : resource.toCharArray();
        this.sourceFileName = IndexTypeEncoding.sourceFile(entry).toCharArray();
        this.enclosingTypeName = enclosingTypeName(entry.jvmOwnerName());
        String superName = encoding.superName();
        this.superclassName = superName == null ? null : superName.toCharArray();
        this.interfaceNames = toCharArrays(encoding.interfaceNames());
        String signature = encoding.classSignature();
        this.genericSignature = signature == null ? null : signature.toCharArray();
        this.permittedSubtypes = permitted(entry);
        this.modifiers = modifiers(entry, index, order);
        this.tagBits = IndexBinaryAccessFlags.annotationTagBits(annotationsOf(entry));
        this.record = IndexBinaryAccessFlags.isRecord(entry);
        this.member = enclosingTypeName != null;
        this.fields = fields(entry, encoding);
        this.methods = methods(entry, encoding, this.modifiers);
        this.memberTypes = memberTypes(entry, index, order);
        this.recordComponents = recordComponents(entry, encoding);
        this.annotations = IndexBinaryAnnotations.of(annotationsOf(entry), encoding);
        this.uri = safeUri(resource);
    }

    @Override
    public IBinaryAnnotation[] getAnnotations() {
        return annotations;
    }

    @Override
    public IBinaryTypeAnnotation[] getTypeAnnotations() {
        return null;
    }

    @Override
    public char[] getEnclosingMethod() {
        return null;
    }

    @Override
    public char[] getEnclosingTypeName() {
        return enclosingTypeName;
    }

    @Override
    public IBinaryField[] getFields() {
        return fields;
    }

    @Override
    public IRecordComponent[] getRecordComponents() {
        return recordComponents;
    }

    @Override
    public char[] getModule() {
        return null;
    }

    @Override
    public char[] getGenericSignature() {
        return genericSignature;
    }

    @Override
    public char[][] getInterfaceNames() {
        return interfaceNames;
    }

    @Override
    public char[][] getPermittedSubtypesNames() {
        return permittedSubtypes;
    }

    @Override
    public IBinaryNestedType[] getMemberTypes() {
        return memberTypes;
    }

    @Override
    public IBinaryMethod[] getMethods() {
        return methods;
    }

    @Override
    public char[][][] getMissingTypeNames() {
        return null;
    }

    @Override
    public char[] getName() {
        return name;
    }

    @Override
    public char[] getSourceName() {
        return sourceName;
    }

    @Override
    public char[] getSuperclassName() {
        return superclassName;
    }

    @Override
    public long getTagBits() {
        return tagBits;
    }

    @Override
    public boolean isAnonymous() {
        return false;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isRecord() {
        return record;
    }

    @Override
    public boolean isMember() {
        return member;
    }

    @Override
    public char[] sourceFileName() {
        return sourceFileName;
    }

    @Override
    public ITypeAnnotationWalker enrichWithExternalAnnotationsFor(
            ITypeAnnotationWalker walker, Object member, LookupEnvironment environment) {
        return walker;
    }

    @Override
    public ExternalAnnotationStatus getExternalAnnotationStatus() {
        return ExternalAnnotationStatus.NO_EEA_FILE;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public int getModifiers() {
        return modifiers;
    }

    @Override
    public boolean isBinaryType() {
        return true;
    }

    @Override
    public char[] getFileName() {
        return fileName;
    }

    /**
     * Modifiers of a member type include the ones its declaration site implies
     * but never spells out - every member of an interface is public and static,
     * and a nested record, enum or interface is always static. The indexer only
     * records explicit modifiers, and ECJ reads a member's access from its own
     * binding rather than from the enclosing type's nested-type table, so
     * without this a nested {@code record} in an interface looks package-private
     * and is rejected as "not visible" outside its package.
     */
    private static int modifiers(TypeEntry entry, Index index, ClasspathOrder classpath) {
        String jvmName = entry.jvmOwnerName();
        int dollar = jvmName.lastIndexOf('$');
        if (dollar <= 0) return IndexBinaryAccessFlags.classModifiers(entry);
        TypeEntry outer = classpath.pick(index.getAll(jvmName.substring(0, dollar)), TypeEntry::sourceUri);
        return outer == null
                ? IndexBinaryAccessFlags.classModifiers(entry)
                : IndexBinaryAccessFlags.innerClassModifiers(outer, entry);
    }

    private static IBinaryField[] fields(TypeEntry entry, IndexTypeEncoding encoding) {
        FieldEntry[] fields = entry.fields();
        if (fields.length == 0) return null;
        IBinaryField[] out = new IBinaryField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            out[i] = new IndexBinaryField(fields[i], entry, encoding);
        }
        return out;
    }

    private static IBinaryMethod[] methods(TypeEntry entry, IndexTypeEncoding encoding, int classModifiers) {
        List<IBinaryMethod> out = new ArrayList<>(entry.methods().length + 3);
        Set<String> declared = new HashSet<>();
        boolean hasConstructor = false;
        boolean hasValues = false;
        boolean hasValueOf = false;
        for (MethodEntry method : entry.methods()) {
            if ("<clinit>".equals(method.name())) continue;
            if ("<init>".equals(method.name())) hasConstructor = true;
            if ("values".equals(method.name())) hasValues = true;
            if ("valueOf".equals(method.name())) hasValueOf = true;
            declared.add(method.name() + encoding.methodDescriptor(method));
            out.add(new IndexBinaryMethod(method, entry, encoding));
        }
        addRecordMembers(entry, encoding, out, declared, visibilityOf(classModifiers));
        if (needsDefaultConstructor(entry, hasConstructor)) {
            out.add(new IndexBinaryMethod("<init>", "()V", visibilityOf(classModifiers)));
        }
        if (entry instanceof SourceTypeEntry source && source.declKind() == TypeDeclKind.ENUM) {
            String own = "L" + entry.jvmOwnerName() + ";";
            if (!hasValues) {
                out.add(new IndexBinaryMethod(
                        "values",
                        "()[" + own,
                        ClassFileConstants.AccPublic | ClassFileConstants.AccStatic));
            }
            if (!hasValueOf) {
                out.add(new IndexBinaryMethod(
                        "valueOf",
                        "(Ljava/lang/String;)" + own,
                        ClassFileConstants.AccPublic | ClassFileConstants.AccStatic));
            }
        }
        return out.isEmpty() ? null : out.toArray(IBinaryMethod[]::new);
    }

    private static boolean needsDefaultConstructor(TypeEntry entry, boolean hasConstructor) {
        if (hasConstructor) return false;
        if (!(entry instanceof SourceTypeEntry source)) return false;
        return source.declKind() != TypeDeclKind.INTERFACE
                && source.declKind() != TypeDeclKind.ANNOTATION
                && source.declKind() != TypeDeclKind.ENUM
                && source.declKind() != TypeDeclKind.RECORD;
    }

    /**
     * Add the members a record gets implicitly: one accessor per component and
     * the canonical constructor. javac writes these into the class file, so a
     * bytecode-derived entry already carries them, but an entry indexed from
     * source only has what the header declares. Without them every
     * {@code point.x()} call and {@code new Point(1, 2)} on a workspace record
     * fails to resolve. A component whose accessor or canonical constructor is
     * spelled out explicitly in the body keeps that declaration.
     */
    private static void addRecordMembers(TypeEntry entry, IndexTypeEncoding encoding,
                                         List<IBinaryMethod> out, Set<String> declared,
                                         int visibility) {
        if (!IndexBinaryAccessFlags.isRecord(entry)) return;
        // A component-less record still has a canonical constructor - the
        // no-argument one - so the loop below may legitimately do nothing.
        RecordComponentEntry[] components = entry.recordComponents();

        for (RecordComponentEntry component : components) {
            String descriptor = "()" + encoding.descriptor(component.type());
            if (!declared.add(component.name() + descriptor)) continue;
            String returned = encoding.fieldSignature(component.type());
            out.add(new IndexBinaryMethod(component.name(), descriptor,
                    returned == null ? null : "()" + returned,
                    null, ClassFileConstants.AccPublic));
        }

        StringBuilder descriptor = new StringBuilder("(");
        StringBuilder signature = new StringBuilder("(");
        boolean generic = false;
        char[][] parameterNames = new char[components.length][];
        for (int i = 0; i < components.length; i++) {
            descriptor.append(encoding.descriptor(components[i].type()));
            signature.append(encoding.signature(components[i].type()));
            generic |= IndexTypeEncoding.needsSignature(components[i].type());
            parameterNames[i] = components[i].name().toCharArray();
        }
        descriptor.append(")V");
        signature.append(")V");
        if (!declared.add("<init>" + descriptor)) return;
        out.add(new IndexBinaryMethod("<init>", descriptor.toString(),
                generic ? signature.toString() : null, parameterNames, visibility));
    }

    /** The canonical constructor of a record, and the default constructor of a
     * class, are as accessible as the type that declares them. */
    private static int visibilityOf(int classModifiers) {
        return classModifiers & (ClassFileConstants.AccPublic
                | ClassFileConstants.AccProtected
                | ClassFileConstants.AccPrivate);
    }

    private static IBinaryNestedType[] memberTypes(
            TypeEntry entry, Index index, ClasspathOrder classpath) {
        String[] inners = entry.innerTypeJvmNames();
        if (inners.length == 0) return null;
        IBinaryNestedType[] out = new IBinaryNestedType[inners.length];
        for (int i = 0; i < inners.length; i++) {
            TypeEntry inner = classpath.pick(index.getAll(inners[i]), TypeEntry::sourceUri);
            int access = inner == null
                    ? ClassFileConstants.AccPublic | ClassFileConstants.AccStatic
                    : IndexBinaryAccessFlags.innerClassModifiers(entry, inner);
            out[i] = new IndexBinaryNestedType(entry.jvmOwnerName(), inners[i], access);
        }
        return out;
    }

    private static IRecordComponent[] recordComponents(TypeEntry entry, IndexTypeEncoding encoding) {
        if (!IndexBinaryAccessFlags.isRecord(entry)) return null;
        RecordComponentEntry[] components = entry.recordComponents();
        if (components.length == 0) return null;
        IRecordComponent[] out = new IRecordComponent[components.length];
        for (int i = 0; i < components.length; i++) {
            out[i] = new IndexBinaryRecordComponent(components[i], encoding);
        }
        return out;
    }

    private static char[][] permitted(TypeEntry entry) {
        TypeRef[] permitted = entry.permittedSubclasses();
        if (permitted.length == 0) return null;
        char[][] names = new char[permitted.length][];
        for (int i = 0; i < permitted.length; i++) {
            names[i] = switch (permitted[i]) {
                case TypeRef.Resolved resolved -> resolved.jvmBinaryName().toCharArray();
                case TypeRef.Unresolved unresolved -> unresolved.simpleName().replace('.', '/').toCharArray();
            };
        }
        return names;
    }

    private static AnnotationRef[] annotationsOf(TypeEntry entry) {
        return switch (entry) {
            case SourceTypeEntry source -> source.annotations();
            case ClassFileTypeEntry classFile -> classFile.annotations();
        };
    }

    private static char[] enclosingTypeName(String jvmName) {
        int dollar = jvmName.lastIndexOf('$');
        if (dollar <= 0) return null;
        return jvmName.substring(0, dollar).toCharArray();
    }

    private static String sourceName(String jvmName) {
        int slash = jvmName.lastIndexOf('/');
        int dollar = jvmName.lastIndexOf('$');
        int start = Math.max(slash, dollar) + 1;
        return jvmName.substring(start);
    }

    private static char[][] toCharArrays(String[] values) {
        if (values == null || values.length == 0) return null;
        char[][] out = new char[values.length][];
        for (int i = 0; i < values.length; i++) out[i] = values[i].toCharArray();
        return out;
    }

    private static URI safeUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
