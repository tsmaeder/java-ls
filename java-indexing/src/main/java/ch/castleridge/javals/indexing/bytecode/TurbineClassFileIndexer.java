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
package ch.castleridge.javals.indexing.bytecode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;

import com.google.turbine.bytecode.ClassFile;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo;
import com.google.turbine.bytecode.ClassFile.AnnotationInfo.ElementValue;
import com.google.turbine.bytecode.ClassFile.FieldInfo;
import com.google.turbine.bytecode.ClassFile.InnerClass;
import com.google.turbine.bytecode.ClassFile.MethodInfo;
import com.google.turbine.bytecode.ClassFile.ModuleInfo;
import com.google.turbine.bytecode.ClassFile.RecordInfo;
import com.google.turbine.bytecode.ClassReader;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineFlag;

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
import ch.castleridge.javals.indexing.model.SignatureRefs;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Reads a single {@code .class} file with Google Turbine's bytecode reader
 * and appends a {@link TypeEntry} / {@link ModuleEntry} into the supplied
 * {@link Index}.
 *
 * <p>Visibility filtering and descriptor/signature parsing match
 * {@link ClassFileIndexer}. Bloom filters are built from the parsed
 * {@link ClassFile} surface (member and type names), not the raw constant
 * pool, so they may omit types that appear only in method bodies.
 */
public final class TurbineClassFileIndexer {

    public static final BytecodeIndexer INSTANCE = TurbineClassFileIndexer::index;

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

    private TurbineClassFileIndexer() {}

    public static void index(String resourcePath, String sourceUri, byte[] bytes, Index into) {
        ClassFile cf = ClassReader.read(resourcePath, bytes);
        ModuleEntry module = toModuleEntry(resourcePath, sourceUri, cf);
        if (module != null) {
            into.addModule(module);
            return;
        }
        TypeEntry entry = toTypeEntry(resourcePath, sourceUri, cf);
        if (entry != null) {
            into.add(entry);
            if (resourcePath != null || sourceUri != null) {
                into.registerBloom(sourceUri, resourcePath,
                        IdentifierBloomFilter.create(simpleNamesFromClassFile(cf)));
            }
        }
    }

    private static ModuleEntry toModuleEntry(String resourcePath, String sourceUri, ClassFile cf) {
        ModuleInfo mod = cf.module();
        if (mod == null) {
            return null;
        }
        List<ModuleEntry.Requires> requires = new ArrayList<>();
        for (Object o : mod.requires()) {
            ModuleInfo.RequireInfo r = (ModuleInfo.RequireInfo) o;
            requires.add(new ModuleEntry.Requires(r.moduleName(), r.flags(), r.version()));
        }
        List<ModuleEntry.Exports> exports = new ArrayList<>();
        for (Object o : mod.exports()) {
            ModuleInfo.ExportInfo e = (ModuleInfo.ExportInfo) o;
            exports.add(new ModuleEntry.Exports(e.moduleName(), toStringArray(e.modules()), e.flags()));
        }
        List<ModuleEntry.Opens> opens = new ArrayList<>();
        for (Object o : mod.opens()) {
            ModuleInfo.OpenInfo op = (ModuleInfo.OpenInfo) o;
            opens.add(new ModuleEntry.Opens(op.moduleName(), toStringArray(op.modules()), op.flags()));
        }
        List<String> uses = new ArrayList<>();
        for (Object o : mod.uses()) {
            ModuleInfo.UseInfo u = (ModuleInfo.UseInfo) o;
            if (u.descriptor() != null) {
                uses.add(u.descriptor());
            }
        }
        List<ModuleEntry.Provides> provides = new ArrayList<>();
        for (Object o : mod.provides()) {
            ModuleInfo.ProvideInfo p = (ModuleInfo.ProvideInfo) o;
            provides.add(new ModuleEntry.Provides(p.descriptor(), toStringArray(p.implDescriptors())));
        }
        // Turbine's ClassFile omits ModulePackages / ModuleMainClass; leave empty/null.
        return new ModuleEntry(
                resourcePath,
                sourceUri,
                mod.name(),
                mod.version(),
                mod.flags(),
                EmptyArrays.toArray(requires, EmptyArrays.REQUIRES),
                EmptyArrays.toArray(exports, EmptyArrays.EXPORTS),
                EmptyArrays.toArray(opens, EmptyArrays.OPENS),
                EmptyArrays.toArray(uses, EmptyArrays.STRING),
                EmptyArrays.toArray(provides, EmptyArrays.PROVIDES),
                EmptyArrays.STRING,
                null);
    }

    private static TypeEntry toTypeEntry(String resourcePath, String sourceUri, ClassFile cf) {
        String jvmName = cf.name();
        if (jvmName == null || Index.isSkippedJvmName(jvmName)) {
            return null;
        }
        int access = cf.access();
        for (Object o : cf.innerClasses()) {
            InnerClass inner = (InnerClass) o;
            if (jvmName.equals(inner.innerClass())) {
                access |= (inner.access() & INNER_CLASS_ACCESS_MASK);
            }
        }
        if (!AccessVisibility.shouldIndexType(access)) {
            return null;
        }

        Type superRef = null;
        List<Type> interfaces = new ArrayList<>();
        List<TypeParamRef> typeParams = List.of();
        String signature = cf.signature();
        if (signature != null) {
            typeParams = SignatureRefs.parseFormalTypeParameters(signature);
            SignatureRefs.ClassRefs classRefs = SignatureRefs.parseClass(signature);
            if (classRefs != null) {
                if (classRefs.superClass() != null) {
                    superRef = classRefs.superClass();
                }
                if (!classRefs.interfaces().isEmpty()) {
                    interfaces = new ArrayList<>(classRefs.interfaces());
                }
            }
        }
        if (superRef == null && cf.superName() != null) {
            superRef = TypeRef.resolved(cf.superName());
        }
        if (interfaces.isEmpty()) {
            for (Object o : cf.interfaces()) {
                interfaces.add(TypeRef.resolved((String) o));
            }
        }

        List<FieldEntry> fields = new ArrayList<>();
        for (Object o : cf.fields()) {
            FieldEntry field = toFieldEntry((FieldInfo) o);
            if (field != null) {
                fields.add(field);
            }
        }

        List<MethodEntry> methods = new ArrayList<>();
        for (Object o : cf.methods()) {
            MethodEntry method = toMethodEntry((MethodInfo) o);
            if (method != null) {
                methods.add(method);
            }
        }

        List<String> innerTypes = new ArrayList<>();
        for (Object o : cf.innerClasses()) {
            InnerClass inner = (InnerClass) o;
            if (jvmName.equals(inner.outerClass()) && AccessVisibility.shouldIndexType(inner.access())) {
                innerTypes.add(inner.innerClass());
            }
        }

        List<TypeRef> permitted = new ArrayList<>();
        for (Object o : cf.permits()) {
            String p = (String) o;
            if (p != null && !p.isEmpty()) {
                permitted.add(TypeRef.resolved(p));
            }
        }

        List<RecordComponentEntry> recordComponents = new ArrayList<>();
        RecordInfo record = cf.record();
        if (record != null) {
            for (Object o : record.recordComponents()) {
                RecordInfo.RecordComponentInfo rc = (RecordInfo.RecordComponentInfo) o;
                Type componentType = rc.signature() != null
                        ? SignatureRefs.parseType(rc.signature())
                        : Descriptors.parseField(rc.descriptor());
                if (componentType == null) {
                    componentType = Descriptors.parseField(rc.descriptor());
                }
                recordComponents.add(new RecordComponentEntry(
                        rc.name(),
                        componentType,
                        EmptyArrays.toArray(toAnnotationRefs(rc.annotations()), EmptyArrays.ANNOTATION_REF)));
            }
        }

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
                EmptyArrays.toArray(permitted, EmptyArrays.TYPE_REF),
                EmptyArrays.toArray(recordComponents, EmptyArrays.RECORD_COMPONENT),
                EmptyArrays.toArray(toAnnotationRefs(cf.annotations()), EmptyArrays.ANNOTATION_REF));
    }

    private static FieldEntry toFieldEntry(FieldInfo field) {
        if (!AccessVisibility.shouldIndexMember(field.access(), field.name())) {
            return null;
        }
        Type fieldType = field.signature() != null
                ? SignatureRefs.parseType(field.signature())
                : Descriptors.parseField(field.descriptor());
        if (fieldType == null) {
            fieldType = Descriptors.parseField(field.descriptor());
        }
        return new FieldEntry(
                field.access(),
                field.name(),
                fieldType,
                constToBoxed(field.value()),
                EmptyArrays.toArray(toAnnotationRefs(field.annotations()), EmptyArrays.ANNOTATION_REF));
    }

    private static MethodEntry toMethodEntry(MethodInfo method) {
        if (!AccessVisibility.shouldIndexMember(method.access(), method.name())) {
            return null;
        }
        Descriptors.MethodRefs parts = Descriptors.parseMethod(method.descriptor());
        SignatureRefs.MethodRefs generic = method.signature() == null
                ? null
                : SignatureRefs.parseMethod(method.signature());
        Type returnType = generic != null && generic.returnType() != null
                ? generic.returnType()
                : parts.returnType();
        List<Type> paramTypes = generic != null && !generic.paramTypes().isEmpty()
                ? generic.paramTypes()
                : parts.paramTypes();
        List<TypeParamRef> methodTypeParams = generic == null ? List.of() : generic.typeParams();
        List<Type> throwsRefs;
        if (generic != null && !generic.throwsTypes().isEmpty()) {
            throwsRefs = generic.throwsTypes();
        } else if (method.exceptions() == null || method.exceptions().isEmpty()) {
            throwsRefs = List.of();
        } else {
            List<Type> ts = new ArrayList<>(method.exceptions().size());
            for (Object e : method.exceptions()) {
                ts.add(TypeRef.resolved((String) e));
            }
            throwsRefs = List.copyOf(ts);
        }

        ParameterEntry[] params = new ParameterEntry[paramTypes.size()];
        List<?> turbineParams = method.parameters();
        List<?> paramAnns = method.parameterAnnotations();
        for (int i = 0; i < paramTypes.size(); i++) {
            String pName = null;
            int pAccess = 0;
            if (turbineParams != null && i < turbineParams.size()) {
                MethodInfo.ParameterInfo pi = (MethodInfo.ParameterInfo) turbineParams.get(i);
                pName = pi.name();
                pAccess = pi.access();
            }
            List<AnnotationRef> anns = List.of();
            if (paramAnns != null && i < paramAnns.size()) {
                anns = toAnnotationRefs((List<?>) paramAnns.get(i));
            }
            params[i] = new ParameterEntry(
                    pName,
                    pAccess,
                    paramTypes.get(i),
                    EmptyArrays.toArray(anns, EmptyArrays.ANNOTATION_REF));
        }

        boolean varargs = (method.access() & TurbineFlag.ACC_VARARGS) != 0;
        boolean hasBody = (method.access() & (TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_NATIVE)) == 0;
        AnnotationValue annotationDefault = method.defaultValue() == null
                ? null
                : toAnnotationValue(method.defaultValue());

        return new MethodEntry(
                method.access(),
                method.name(),
                returnType,
                params,
                EmptyArrays.toArray(throwsRefs, EmptyArrays.TYPE),
                EmptyArrays.toArray(methodTypeParams, EmptyArrays.TYPE_PARAM),
                varargs,
                hasBody,
                annotationDefault,
                EmptyArrays.toArray(toAnnotationRefs(method.annotations()), EmptyArrays.ANNOTATION_REF));
    }

    private static List<AnnotationRef> toAnnotationRefs(List<?> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }
        List<AnnotationRef> out = new ArrayList<>(annotations.size());
        for (Object o : annotations) {
            out.add(toAnnotationRef((AnnotationInfo) o));
        }
        return out;
    }

    private static AnnotationRef toAnnotationRef(AnnotationInfo info) {
        Map<String, AnnotationValue> values = new HashMap<>();
        for (Map.Entry<String, ElementValue> e : info.elementValuePairs().entrySet()) {
            values.put(e.getKey(), toAnnotationValue(e.getValue()));
        }
        return new AnnotationRef(TypeRef.resolved(jvmNameForDescriptor(info.typeName())), values);
    }

    private static AnnotationValue toAnnotationValue(ElementValue value) {
        if (value == null) {
            return new AnnotationValue.Unsupported("null");
        }
        return switch (value.kind()) {
            case CONST -> {
                Const.Value cv = ((ElementValue.ConstValue) value).value();
                if (cv instanceof Const.StringValue s) {
                    yield new AnnotationValue.Str(s.value());
                }
                Object boxed = constToBoxed(cv);
                yield boxed == null
                        ? new AnnotationValue.Unsupported("null const")
                        : new AnnotationValue.Primitive(boxed);
            }
            case ENUM -> {
                ElementValue.EnumConstValue e = (ElementValue.EnumConstValue) value;
                yield new AnnotationValue.EnumConst(
                        Descriptors.parseField(e.typeName()),
                        e.constName());
            }
            case CLASS -> {
                String className = ((ElementValue.ConstTurbineClassValue) value).className();
                yield new AnnotationValue.ClassRef(classNameToType(className));
            }
            case ARRAY -> {
                ElementValue.ArrayValue arr = (ElementValue.ArrayValue) value;
                AnnotationValue[] elements = new AnnotationValue[arr.elements().size()];
                int i = 0;
                for (Object el : arr.elements()) {
                    elements[i++] = toAnnotationValue((ElementValue) el);
                }
                yield new AnnotationValue.Arr(elements);
            }
            case ANNOTATION -> new AnnotationValue.Nested(
                    toAnnotationRef(((ElementValue.ConstTurbineAnnotationValue) value).annotation()));
        };
    }

    private static Type classNameToType(String className) {
        if (className == null || className.isEmpty()) {
            return TypeRef.resolved("java/lang/Object");
        }
        // Turbine may store a field descriptor or an internal name.
        if (className.charAt(0) == '[' || className.length() == 1
                || (className.charAt(0) == 'L' && className.endsWith(";"))) {
            return Descriptors.parseField(className);
        }
        return TypeRef.resolved(className);
    }

    private static Object constToBoxed(Const.Value value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Const.BooleanValue v) return v.value();
        if (value instanceof Const.ByteValue v) return v.value();
        if (value instanceof Const.ShortValue v) return v.value();
        if (value instanceof Const.CharValue v) return v.value();
        if (value instanceof Const.IntValue v) return v.value();
        if (value instanceof Const.LongValue v) return v.value();
        if (value instanceof Const.FloatValue v) return v.value();
        if (value instanceof Const.DoubleValue v) return v.value();
        if (value instanceof Const.StringValue v) return v.value();
        return value.getValue();
    }

    private static String jvmNameForDescriptor(String descriptor) {
        Type ref = Descriptors.parseField(descriptor);
        if (ref instanceof TypeRef.Resolved r) {
            return r.jvmBinaryName();
        }
        return descriptor;
    }

    private static String[] toStringArray(List<?> list) {
        if (list == null || list.isEmpty()) {
            return EmptyArrays.STRING;
        }
        String[] out = new String[list.size()];
        int n = 0;
        for (Object o : list) {
            if (o != null) {
                out[n++] = o.toString();
            }
        }
        if (n == 0) {
            return EmptyArrays.STRING;
        }
        if (n == out.length) {
            return out;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Collect simple names from the parsed classfile surface for Bloom
     * registration. Unlike ASM's constant-pool walk, this does not see
     * types referenced only from method bodies.
     */
    static Set<String> simpleNamesFromClassFile(ClassFile cf) {
        Set<String> names = new HashSet<>();
        addSimpleName(names, cf.name());
        addSimpleName(names, cf.superName());
        for (Object o : cf.interfaces()) {
            addSimpleName(names, (String) o);
        }
        for (Object o : cf.permits()) {
            addSimpleName(names, (String) o);
        }
        for (Object o : cf.fields()) {
            FieldInfo f = (FieldInfo) o;
            addSimpleName(names, f.name());
            addNamesFromDescriptor(names, f.descriptor());
            addNamesFromSignature(names, f.signature());
        }
        for (Object o : cf.methods()) {
            MethodInfo m = (MethodInfo) o;
            addSimpleName(names, m.name());
            addNamesFromDescriptor(names, m.descriptor());
            addNamesFromSignature(names, m.signature());
            for (Object e : m.exceptions()) {
                addSimpleName(names, (String) e);
            }
        }
        for (Object o : cf.innerClasses()) {
            InnerClass inner = (InnerClass) o;
            addSimpleName(names, inner.innerClass());
            addSimpleName(names, inner.outerClass());
            addSimpleName(names, inner.innerName());
        }
        for (AnnotationRef ann : toAnnotationRefs(cf.annotations())) {
            addSimpleName(names, ann.jvmName());
        }
        return names;
    }

    private static void addNamesFromDescriptor(Set<String> names, String descriptor) {
        if (descriptor == null) {
            return;
        }
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) != 'L') {
                continue;
            }
            int end = descriptor.indexOf(';', i);
            if (end < 0) {
                break;
            }
            addSimpleName(names, descriptor.substring(i + 1, end));
            i = end;
        }
    }

    private static void addNamesFromSignature(Set<String> names, String signature) {
        if (signature == null) {
            return;
        }
        addNamesFromDescriptor(names, signature.replace('.', '/'));
    }

    private static void addSimpleName(Set<String> names, String internal) {
        if (internal == null || internal.isEmpty() || internal.charAt(0) == '[') {
            return;
        }
        int cut = Math.max(internal.lastIndexOf('/'), internal.lastIndexOf('$'));
        names.add(cut < 0 ? internal : internal.substring(cut + 1));
    }
}
