package ch.castleridge.javals.javac;

import java.util.Iterator;

import java.util.Map;

import com.sun.tools.javac.code.Attribute;

import com.sun.tools.javac.code.Symbol;

import com.sun.tools.javac.code.Symbol.ClassSymbol;

import com.sun.tools.javac.code.Symbol.CompletionFailure;

import com.sun.tools.javac.code.Symbol.MethodSymbol;

import com.sun.tools.javac.code.Symbol.ModuleSymbol;

import com.sun.tools.javac.code.Symbol.VarSymbol;

import com.sun.tools.javac.code.Symtab;

import com.sun.tools.javac.code.Type;

import com.sun.tools.javac.code.Type.ArrayType;

import com.sun.tools.javac.code.Types;

import com.sun.tools.javac.util.List;

import com.sun.tools.javac.util.ListBuffer;

import com.sun.tools.javac.util.Name;

import com.sun.tools.javac.util.Names;

import com.sun.tools.javac.util.Pair;

import ch.castleridge.javals.indexing.model.AnnotationRef;

import ch.castleridge.javals.indexing.model.AnnotationValue;

import ch.castleridge.javals.indexing.model.Type.Array;

import ch.castleridge.javals.indexing.model.Type.Primitive;

import ch.castleridge.javals.indexing.model.TypeEntry;

import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * 
 * Convert indexed {@link AnnotationRef} / {@link AnnotationValue} trees
 * 
 * into javac {@link Attribute} instances so they can be attached to the
 * 
 * symbols built by {@link IndexClassReader}.
 *
 * 
 * 
 * <p>
 * The converter resolves annotation type symbols via
 * 
 * {@link TypeRefResolver} and enum constant {@link VarSymbol}s lazily via
 * 
 * {@link Symtab#enterClass(ModuleSymbol, Name)} and
 * 
 * {@link ClassSymbol#complete()}; completion failures (missing
 * 
 * dependency, recursive completion already in progress) collapse to
 * 
 * {@link Attribute.Error} so that javac can still finish typing the
 * 
 * surrounding declaration.
 *
 * 
 * 
 * <p>
 * The expected element type drives a couple of source-side
 * 
 * accommodations: scalar values supplied where an array element is
 * 
 * declared are wrapped in a single-element {@link Attribute.Array}
 * 
 * (Java's single-element shorthand), and bare-identifier
 * 
 * {@link AnnotationValue.EnumConst} values with an unresolved qualifier
 * 
 * are bound against the declared enum type.
 * 
 */

final class IndexAnnotations {

    private final Symtab syms;

    private final Names names;

    private final Types types;

    private final TypeRefResolver resolver;

    IndexAnnotations(Symtab syms, Names names, Types types, TypeRefResolver resolver) {

        this.syms = syms;

        this.names = names;

        this.types = types;

        this.resolver = resolver;

    }

    /** Convert a list of indexed annotation refs into javac compound attributes. */

    List<Attribute.Compound> toCompounds(java.util.List<AnnotationRef> refs,

            ModuleSymbol module,

            TypeEntry enclosing) {

        if (refs == null || refs.isEmpty())
            return List.nil();

        ListBuffer<Attribute.Compound> out = new ListBuffer<>();

        for (AnnotationRef ref : refs) {

            Attribute.Compound compound = toCompound(ref, module, enclosing);

            if (compound != null)
                out.add(compound);

        }

        return out.toList();

    }

    /**
     * 
     * Convert indexed type-use annotations into javac
     * 
     * {@link Attribute.TypeCompound}s. We don't have a precise
     * 
     * {@code TypeAnnotationPosition} (the indexer encodes location
     * 
     * structurally on the {@link ch.castleridge.javals.indexing.model.Type}
     * 
     * tree via the {@code Annotated}
     * 
     * decorator, not via the JVMS path), so each compound is built with
     * 
     * {@code TypeAnnotationPosition.unknown}. Callers attach the result
     * 
     * with {@code Type.annotatedType(...)}.
     * 
     */

    List<Attribute.TypeCompound> toTypeCompounds(java.util.List<AnnotationRef> refs,

            ModuleSymbol module,

            TypeEntry enclosing) {

        if (refs == null || refs.isEmpty())
            return List.nil();

        ListBuffer<Attribute.TypeCompound> out = new ListBuffer<>();

        for (AnnotationRef ref : refs) {

            Attribute.Compound base = toCompound(ref, module, enclosing);

            if (base == null)
                continue;

            out.add(new Attribute.TypeCompound(base,

                    com.sun.tools.javac.code.TypeAnnotationPosition.unknown));

        }

        return out.toList();

    }

    /**
     * 
     * Build a single {@link Attribute.Compound}. Returns {@code null} if
     * 
     * the annotation type cannot be resolved at all. Elements that
     * 
     * cannot be matched against a declared annotation element method
     * 
     * are silently dropped, matching javac's "unspecified element"
     * 
     * semantics.
     * 
     */

    Attribute.Compound toCompound(AnnotationRef ref, ModuleSymbol module, TypeEntry enclosing) {

        if (ref == null)
            return null;

        // Guard the whole conversion against CompletionFailure: a missing

        // annotation type (e.g. an unbuilt annotation-processor dependency)

        // must not abort the enclosing class read. javac itself keeps the

        // annotated declaration usable, so without this a single missing

        // annotation type would make the annotated type look absent

        // ("cannot find symbol").

        try {

            return toCompoundUnguarded(ref, module, enclosing);

        } catch (CompletionFailure ignored) {

            return null;

        }

    }

    private Attribute.Compound toCompoundUnguarded(AnnotationRef ref, ModuleSymbol module, TypeEntry enclosing) {

        if (ref == null)
            return null;

        Type annType = resolver.resolve(ref.annotationType(), module, enclosing);

        if (annType == null || annType.isErroneous() || !(annType.tsym instanceof ClassSymbol annSym)) {
            return null;
        }

        try {

            annSym.complete();

        } catch (CompletionFailure ignored) {

            // Best-effort: members may still be usable.

        }

        ListBuffer<Pair<MethodSymbol, Attribute>> pairs = new ListBuffer<>();

        for (Map.Entry<String, AnnotationValue> e : ref.values().entrySet()) {

            MethodSymbol element = findElement(annSym, e.getKey());

            if (element == null)
                continue;

            Type elementType = element.getReturnType();

            Attribute attr = toAttribute(e.getValue(), elementType, module, enclosing);

            if (attr == null)
                continue;

            pairs.add(new Pair<>(element, attr));

        }

        return new Attribute.Compound(annSym.type, pairs.toList());

    }

    /**
     * 
     * Convert one {@link AnnotationValue} into an {@link Attribute},
     * 
     * coercing into the element's declared {@code expectedType} when
     * 
     * possible (e.g. wrapping a scalar value into a single-element
     * 
     * array, or binding an unresolved enum constant against the
     * 
     * declared enum type).
     * 
     */

    Attribute toAttribute(AnnotationValue value, Type expectedType,

            ModuleSymbol module, TypeEntry enclosing) {

        if (value == null)
            return null;

        if (value instanceof AnnotationValue.Unsupported)
            return null;

        // Java single-element shorthand: declared element is array-typed

        // but source supplied a scalar literal. Wrap into a 1-element

        // Attribute.Array of the proper component type.

        if (expectedType instanceof ArrayType at && !(value instanceof AnnotationValue.Arr)) {

            Attribute single = toAttribute(value, at.elemtype, module, enclosing);

            if (single == null)
                return null;

            return new Attribute.Array(expectedType, new Attribute[] { single });

        }

        if (value instanceof AnnotationValue.Primitive p) {

            Type t = expectedType == null || expectedType.isErroneous() ? defaultPrimitiveType(p.boxed())
                    : expectedType;

            return new Attribute.Constant(t, p.boxed());

        }

        if (value instanceof AnnotationValue.Str s) {

            Type t = expectedType == null || expectedType.isErroneous() ? syms.stringType : expectedType;

            return new Attribute.Constant(t, s.value());

        }

        if (value instanceof AnnotationValue.ClassRef cr) {

            Type classType = resolveTypeRef(cr.type(), module);

            if (classType == null || classType.isErroneous()) {

                return new Attribute.Error(expectedType != null ? expectedType : syms.classType);

            }

            return new Attribute.Class(types, classType);

        }

        if (value instanceof AnnotationValue.EnumConst ec) {

            Type enumType = resolveTypeRef(ec.enumType(), module);

            if (enumType == null || enumType.isErroneous() || isUnresolvedSentinel(ec.enumType())) {

                // Source indexer encoded a bare identifier; bind it

                // against the declared element type.

                enumType = expectedType;

            }

            VarSymbol v = findEnumConstant(enumType, ec.constant());

            if (v == null || enumType == null) {

                return new Attribute.Error(expectedType != null ? expectedType : syms.errType);

            }

            return new Attribute.Enum(enumType, v);

        }

        if (value instanceof AnnotationValue.Arr arr) {

            Type elementType = expectedType instanceof ArrayType at

                    ? at.elemtype

                    : (expectedType != null ? expectedType : syms.objectType);

            Attribute[] arrAttrs = new Attribute[arr.elements().size()];

            int i = 0;

            for (AnnotationValue elem : arr.elements()) {

                Attribute a = toAttribute(elem, elementType, module, enclosing);

                arrAttrs[i++] = a == null ? new Attribute.Error(elementType) : a;

            }

            Type arrayType = expectedType instanceof ArrayType

                    ? expectedType

                    : new ArrayType(elementType, syms.arrayClass);

            return new Attribute.Array(arrayType, arrAttrs);

        }

        if (value instanceof AnnotationValue.Nested n) {

            Attribute.Compound inner = toCompound(n.annotation(), module, enclosing);

            return inner != null ? inner : new Attribute.Error(expectedType != null ? expectedType : syms.errType);

        }

        return null;

    }

    /**
     * 
     * Resolve an indexed {@link ch.castleridge.javals.indexing.model.Type}
     * 
     * into a javac {@link Type}. Only the
     * 
     * resolved shapes that legitimately appear in annotation values
     * 
     * (primitive, array, fully-qualified class refs and source-indexer
     * 
     * unresolved sentinels) are supported.
     * 
     */

    private Type resolveTypeRef(ch.castleridge.javals.indexing.model.Type ref, ModuleSymbol module) {

        if (ref == null)
            return null;

        if (ref instanceof Primitive p) {

            return switch (p) {

                case VOID -> syms.voidType;

                case BOOLEAN -> syms.booleanType;

                case BYTE -> syms.byteType;

                case CHAR -> syms.charType;

                case SHORT -> syms.shortType;

                case INT -> syms.intType;

                case LONG -> syms.longType;

                case FLOAT -> syms.floatType;

                case DOUBLE -> syms.doubleType;

            };

        }

        if (ref instanceof Array a) {

            Type elem = resolveTypeRef(a.element(), module);

            if (elem == null)
                return null;

            return new ArrayType(elem, syms.arrayClass);

        }

        if (ref instanceof TypeRef.Resolved r) {

            ClassSymbol c = enterClass(module, r.jvmBinaryName());

            return c == null ? null : c.type;

        }

        if (ref instanceof TypeRef.Unresolved) {

            // Indexer couldn't pin down the qualifier; rely on the

            // expected element type at the call site.

            return null;

        }

        return null;

    }

    private boolean isUnresolvedSentinel(ch.castleridge.javals.indexing.model.Type ref) {

        return ref instanceof TypeRef.Unresolved;

    }

    private ClassSymbol enterClass(ModuleSymbol module, String jvmBinaryName) {

        if (jvmBinaryName == null || jvmBinaryName.isEmpty())
            return null;

        String dotted = jvmBinaryName.replace('/', '.');

        ClassSymbol c = syms.enterClass(module, names.fromString(dotted));

        try {

            c.complete();

        } catch (CompletionFailure ignored) {

            // Best-effort: the symbol may still be usable as a Type

            // reference even if its members couldn't be loaded.

        }

        return c;

    }

    private MethodSymbol findElement(ClassSymbol annType, String elementName) {

        if (annType == null || annType.members_field == null)
            return null;

        Name n = names.fromString(elementName);

        Iterator<Symbol> it = annType.members_field.getSymbolsByName(n).iterator();

        while (it.hasNext()) {

            Symbol s = it.next();

            if (s instanceof MethodSymbol m)
                return m;

        }

        return null;

    }

    private VarSymbol findEnumConstant(Type enumType, String name) {

        if (enumType == null || !(enumType.tsym instanceof ClassSymbol enumClass))
            return null;

        try {

            enumClass.complete();

        } catch (CompletionFailure ignored) {

            // tolerate; the lookup below will simply find nothing

        }

        if (enumClass.members_field == null)
            return null;

        Name n = names.fromString(name);

        Iterator<Symbol> it = enumClass.members_field.getSymbolsByName(n).iterator();

        while (it.hasNext()) {

            Symbol s = it.next();

            if (s instanceof VarSymbol v)
                return v;

        }

        return null;

    }

    private Type defaultPrimitiveType(Object boxed) {

        if (boxed instanceof Boolean)
            return syms.booleanType;

        if (boxed instanceof Byte)
            return syms.byteType;

        if (boxed instanceof Short)
            return syms.shortType;

        if (boxed instanceof Character)
            return syms.charType;

        if (boxed instanceof Integer)
            return syms.intType;

        if (boxed instanceof Long)
            return syms.longType;

        if (boxed instanceof Float)
            return syms.floatType;

        if (boxed instanceof Double)
            return syms.doubleType;

        return syms.objectType;

    }

}
