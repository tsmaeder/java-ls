package ch.castleridge.javals.javac;

import static com.sun.tools.javac.code.Flags.STATIC;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.Annotate.AnnotationTypeMetadata;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.Type.Annotated;
import ch.castleridge.javals.indexing.model.Type.Array;
import ch.castleridge.javals.indexing.model.Type.Parameterized;
import ch.castleridge.javals.indexing.model.Type.Primitive;
import ch.castleridge.javals.indexing.model.Type.TypeVariable;
import ch.castleridge.javals.indexing.model.Type.Wildcard;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * A {@link ClassReader} that intercepts class files produced by the index.
 *
 * <p>If the {@link ClassSymbol}'s {@code classfile} is an
 * {@link IndexClassFileObject}, the reader builds the symbol's members, type
 * parameters, supertype and interfaces directly from the backing
 * {@link TypeEntry} without ever touching bytecode - using the
 * {@link TypeRefResolver} to turn the indexed {@link ch.castleridge.javals.indexing.model.Type}s emitted by the
 * indexer (which may still carry {@link TypeRef.Unresolved} simple names)
 * into javac {@link Type}s. For any other file object the call falls
 * through to the standard implementation.
 *
 * <p>Resolution of {@link TypeRef.Unresolved} references is filtered by a
 * {@link ClasspathOrder}: only index entries whose source is on the
 * classpath are considered, matching the behaviour of the file manager.
 *
 * <p>Must be registered into the javac {@link Context} <em>before</em> any
 * other code calls {@code ClassReader.instance(context)}. The simplest way
 * is to call {@link #preRegister(Context, Index, ClasspathOrder)} right
 * after constructing the context - the factory defers construction until
 * the file manager is in place.
 */
public final class IndexClassReader extends ClassReader {

    /**
     * Fallback default value for annotation elements whose default is
     * recorded as {@link ch.castleridge.javals.indexing.model.AnnotationValue.Unsupported}
     * (or otherwise non-convertible). {@code Check.validateAnnotation}
     * only consults presence/absence here, so {@link Attribute.Error}
     * is a sound placeholder.
     */
    private static final Attribute ANNOTATION_DEFAULT_SENTINEL = new Attribute.Error(Type.noType);

    private final Index index;
    private final ClasspathOrder classpath;
    private final Symtab syms;
    private final Names names;
    private final Types types;
    private final TypeRefResolver resolver;
    private final IndexAnnotations annotations;

    private IndexClassReader(Context context, Index index, ClasspathOrder classpath) {
        super(context);
        this.index = index;
        this.classpath = classpath;
        this.syms = Symtab.instance(context);
        this.names = Names.instance(context);
        this.types = Types.instance(context);
        this.resolver = new TypeRefResolver(syms, names, index, classpath);
        this.annotations = new IndexAnnotations(syms, names, types, resolver);
    }

    /**
     * Install an {@code IndexClassReader} factory under the supplied
     * context with an {@linkplain ClasspathOrder#UNRESTRICTED unrestricted}
     * classpath view.
     */
    public static void preRegister(Context context, Index index) {
        preRegister(context, index, ClasspathOrder.UNRESTRICTED);
    }

    /**
     * Install an {@code IndexClassReader} factory under the supplied context.
     *
     * <p>The factory pattern is important: at the time {@code preRegister} is
     * called the context typically does not yet contain a
     * {@link javax.tools.JavaFileManager}. {@code JavacTool.getTask} will
     * register the file manager later, and only then (when a javac phase first
     * asks for {@code ClassReader.instance}) do we want to construct the
     * reader - it needs the file manager in {@link ClassReader#ClassReader}.
     */
    public static void preRegister(Context context, Index index, ClasspathOrder classpath) {
        context.put(classReaderKey, (Context.Factory<ClassReader>) ctx -> new IndexClassReader(ctx, index, classpath));
    }

    @Override
    public void readClassFile(ClassSymbol c) {
        if (c.classfile instanceof IndexClassFileObject icfo) {
            fillFromIndex(c, icfo.entry());
            return;
        }
        super.readClassFile(c);
    }

    private void fillFromIndex(ClassSymbol c, TypeEntry entry) {
        ModuleSymbol module = c.packge() == null ? syms.unnamedModule : c.packge().modle;
        if (module == null) module = syms.unnamedModule;

        c.flags_field = IndexAccessFlags.classFlags(entry);
        c.members_field = WriteableScope.create(c);

        registerInnerTypes(c, entry, module);

        ClassType ct = (ClassType) c.type;
        normalizeEnclosingType(c, entry, ct);
        List<Type> classTypeParams = synthesizeTypeParams(c, entry);
        ct.typarams_field = classTypeParams;
        ResolutionContext classCtx = ResolutionContext.of(entry, classTypeParams);

        // Phase 2 of the type-parameter setup: now that the sibling
        // TypeVars are visible through `classCtx`, resolve each declared
        // bound (F-bounded generics like `<T extends Comparable<T>>` need
        // to see T while resolving T's own bound).
        applyTypeParamBounds(classTypeParams, entry.typeParams(), module, classCtx);

        ct.supertype_field = entry.superRef() == null
                ? Type.noType
                : resolveType(entry.superRef(), module, classCtx);

        List<Type> interfaces = List.nil();
        for (ch.castleridge.javals.indexing.model.Type iref : entry.interfaceRefs()) {
            interfaces = interfaces.prepend(resolveType(iref, module, classCtx));
        }
        ct.interfaces_field = interfaces.reverse();

        // Sealed types: capture PermittedSubclasses attribute (bytecode)
        // or `permits` clause (source) so Check.checkSealed and Resolve's
        // class hierarchy walks see the closed set.
        if (!entry.permittedSubclasses().isEmpty()) {
            ListBuffer<Symbol> permitted = new ListBuffer<>();
            for (TypeRef pr : entry.permittedSubclasses()) {
                Type pt = resolver.resolve(pr, module, entry);
                if (pt != null && pt.tsym instanceof ClassSymbol cs) {
                    permitted.add(cs);
                }
            }
            if (permitted.nonEmpty()) {
                c.setPermittedSubclasses(permitted.toList());
                c.flags_field |= Flags.SEALED;
            }
        }

        for (FieldEntry f : entry.fields()) {
            Type t = resolveField(f, module, classCtx);
            VarSymbol v = new VarSymbol(IndexAccessFlags.fieldFlags(entry, f), names.fromString(f.name()), t, c);
            List<Attribute.Compound> fAttrs = annotations.toCompounds(f.annotations(), module, entry);
            v.setDeclarationAttributes(fAttrs);
            v.flags_field |= deprecationFlags(fAttrs);
            // Mirror ClassReader's ConstantValue handling so javac can
            // constant-fold use sites of static final primitives and
            // String constants exposed by indexed classes.
            if (f.constantValue() != null && (v.flags_field & Flags.FINAL) != 0) {
                v.setData(coerceConstantValue(f.constantValue(), t));
            }
            c.members_field.enter(v);
        }

        // Materialise record components before methods so that the
        // accessor-wiring loop below sees the canonical list and we can
        // mirror javac's "RC.accessor = method(rc.name, ())" linkage.
        ListBuffer<ClassSymbol.RecordComponent> recordComponentSyms = null;
        if (!entry.recordComponents().isEmpty()) {
            recordComponentSyms = new ListBuffer<>();
            for (RecordComponentEntry rce : entry.recordComponents()) {
                Type rcType = resolveType(rce.type(), module, classCtx);
                ClassSymbol.RecordComponent rc = new ClassSymbol.RecordComponent(
                        names.fromString(rce.name()), rcType, c);
                rc.setDeclarationAttributes(annotations.toCompounds(rce.annotations(), module, entry));
                recordComponentSyms.add(rc);
            }
            c.setRecordComponents(recordComponentSyms.toList());
        }

        for (MethodEntry m : entry.methods()) {
            List<Type> methodTypeParams = synthesizeMethodTypeParams(c, m);
            ResolutionContext methodCtx =
                    ResolutionContext.of(entry, classTypeParams, methodTypeParams);
            applyTypeParamBounds(methodTypeParams, m.typeParams(), module, methodCtx);
            MethodType mt = resolveMethod(m, module, methodCtx);
            Type methodType = methodTypeParams.isEmpty()
                    ? mt
                    : new Type.ForAll(methodTypeParams, mt);
            MethodSymbol ms = new MethodSymbol(IndexAccessFlags.methodFlags(entry, m), names.fromString(m.name()), methodType, c);
            List<Attribute.Compound> mAttrs = annotations.toCompounds(m.annotations(), module, entry);
            ms.setDeclarationAttributes(mAttrs);
            ms.flags_field |= deprecationFlags(mAttrs);
            // Mirror javac: an interface owning a default method also
            // carries the DEFAULT flag, which downstream phases consult.
            if ((ms.flags_field & Flags.DEFAULT) != 0) {
                c.flags_field |= Flags.DEFAULT;
            }
            populateMethodParameters(ms, m, mt, module, entry);
            if (m.annotationDefault() != null) {
                Attribute defaultAttr = annotations.toAttribute(
                        m.annotationDefault(), mt.getReturnType(), module, entry);
                ms.defaultValue = defaultAttr != null ? defaultAttr : ANNOTATION_DEFAULT_SENTINEL;
            }
            c.members_field.enter(ms);
        }

        // Wire the canonical accessor (the no-arg method named after the
        // component) for each record component, matching ClassReader's
        // post-pass.
        if (recordComponentSyms != null) {
            for (ClassSymbol.RecordComponent rc : recordComponentSyms) {
                rc.accessor = lookupNoArgMethod(c, rc.name);
            }
        }

        // Class-level annotations must be attached before the
        // AnnotationTypeMetadata is built so the metadata can pick up
        // @Target and @Repeatable from the same compounds.
        List<Attribute.Compound> classAttrs = annotations.toCompounds(entry.annotations(), module, entry);
        c.setDeclarationAttributes(classAttrs);
        c.flags_field |= deprecationFlags(classAttrs);

        // Mirror ClassReader.readClassFile: annotation types need a real
        // AnnotationTypeMetadata so Check.validateAnnotation can enumerate
        // their element methods and enforce @Target / find the
        // @Repeatable container. Without this the class keeps the
        // default notAnAnnotationType() metadata, which exposes an empty
        // element set and makes every supplied argument look like a
        // duplicate.
        if ((c.flags_field & Flags.ANNOTATION) != 0) {
            AnnotationTypeMetadata meta = new AnnotationTypeMetadata(c, null);
            populateAnnotationMetadata(meta, classAttrs);
            c.setAnnotationTypeMetadata(meta);
        }

        c.completer = Symbol.Completer.NULL_COMPLETER;
    }

    /**
     * The context needed to resolve indexed types into javac types: the
     * enclosing {@link TypeEntry} (for simple-name class resolution) plus
     * the formal type parameters visible to a type variable at this
     * position (class-level and, when inside a method, method-level).
     */
    record ResolutionContext(
            TypeEntry enclosing,
            List<Type> classTypeParams,
            List<Type> methodTypeParams) {

        static ResolutionContext of(TypeEntry enclosing, List<Type> classTypeParams) {
            return new ResolutionContext(enclosing, classTypeParams, List.nil());
        }

        static ResolutionContext of(
                TypeEntry enclosing,
                List<Type> classTypeParams,
                List<Type> methodTypeParams) {
            return new ResolutionContext(enclosing, classTypeParams, methodTypeParams);
        }
    }

    /**
     * Resolve an indexed {@link ch.castleridge.javals.indexing.model.Type}
     * tree into a javac {@link Type}. Structural shapes are handled here;
     * the class-reference leaves ({@link TypeRef}) are delegated to
     * {@link TypeRefResolver}.
     */
    private Type resolveType(ch.castleridge.javals.indexing.model.Type ref,
                             ModuleSymbol module, ResolutionContext ctx) {
        if (ref == null) return syms.errType;
        if (ref instanceof Annotated annotated) {
            Type inner = resolveType(annotated.inner(), module, ctx);
            List<Attribute.TypeCompound> compounds =
                    annotations.toTypeCompounds(annotated.annotations(), module, ctx.enclosing());
            if (compounds.isEmpty()) return inner;
            return inner.annotatedType(compounds);
        }
        if (ref instanceof Primitive p) return primitive(p);
        if (ref instanceof Array a) {
            return new ArrayType(resolveType(a.element(), module, ctx), syms.arrayClass);
        }
        if (ref instanceof TypeVariable tv) {
            return lookupTypeVar(tv.name(), ctx);
        }
        if (ref instanceof Wildcard w) {
            return resolveWildcard(w, module, ctx);
        }
        if (ref instanceof Parameterized p) {
            return resolveParameterized(p, module, ctx);
        }
        if (ref instanceof TypeRef tr) {
            return resolver.resolve(tr, module, ctx.enclosing());
        }
        return syms.errType;
    }

    private MethodType resolveMethod(MethodEntry m, ModuleSymbol module, ResolutionContext ctx) {
        ListBuffer<Type> params = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type pr : m.paramTypes()) {
            params.add(resolveType(pr, module, ctx));
        }
        ListBuffer<Type> thrown = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type tr : m.throwsTypes()) {
            thrown.add(resolveType(tr, module, ctx));
        }
        Type ret = resolveType(m.returnType(), module, ctx);
        return new MethodType(params.toList(), ret, thrown.toList(), syms.methodClass);
    }

    private Type resolveField(FieldEntry f, ModuleSymbol module, ResolutionContext ctx) {
        return resolveType(f.type(), module, ctx);
    }

    private Type resolveParameterized(Parameterized p, ModuleSymbol module, ResolutionContext ctx) {
        Type raw = resolver.resolve(p.raw(), module, ctx.enclosing());
        ListBuffer<Type> args = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type arg : p.typeArgs()) {
            args.add(resolveType(arg, module, ctx));
        }
        if (raw instanceof ClassType ct) {
            // Static nested types must not carry the raw outer type: propagating it
            // causes Attr to reject uses like Map.Entry<K,V> with "improperly formed
            // type, type arguments given on a raw type". Top-level types already have
            // outer_field == Type.noType, so this is only a correction for static
            // members whose outer_field was initialised to the raw owner by
            // Symtab.defineClass.
            Type outer = (ct.tsym.flags_field & Flags.STATIC) != 0
                    ? Type.noType
                    : ct.getEnclosingType();
            return new ClassType(outer, args.toList(), ct.tsym);
        }
        return raw;
    }

    private Type resolveWildcard(Wildcard w, ModuleSymbol module, ResolutionContext ctx) {
        return switch (w.kind()) {
            case UNBOUNDED -> new WildcardType(syms.objectType, BoundKind.UNBOUND, syms.boundClass);
            case EXTENDS -> new WildcardType(
                    resolveType(w.bound(), module, ctx), BoundKind.EXTENDS, syms.boundClass);
            case SUPER -> new WildcardType(
                    resolveType(w.bound(), module, ctx), BoundKind.SUPER, syms.boundClass);
        };
    }

    private Type lookupTypeVar(String name, ResolutionContext ctx) {
        for (Type t : ctx.methodTypeParams()) {
            if (t instanceof TypeVar tv && tv.tsym.name.contentEquals(name)) {
                return t;
            }
        }
        for (Type t : ctx.classTypeParams()) {
            if (t instanceof TypeVar tv && tv.tsym.name.contentEquals(name)) {
                return t;
            }
        }
        return syms.objectType;
    }

    private Type primitive(Primitive p) {
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

    /**
     * Populate {@code meta.target} / {@code meta.repeatable} from the
     * just-attached declaration attributes so {@code Check} can enforce
     * {@code @Target} target sets and discover {@code @Repeatable}
     * container types for indexed annotation types.
     */
    private static void populateAnnotationMetadata(AnnotationTypeMetadata meta, List<Attribute.Compound> classAttrs) {
        for (Attribute.Compound a : classAttrs) {
            if (a.type == null || a.type.tsym == null) continue;
            String qn = a.type.tsym.getQualifiedName().toString();
            switch (qn) {
                case "java.lang.annotation.Target" -> meta.setTarget(a);
                case "java.lang.annotation.Repeatable" -> meta.setRepeatable(a);
                default -> {
                    // nothing
                }
            }
        }
    }

    /**
     * Mirror javac's {@code ClassReader.attachAnnotations}: an
     * {@code @Deprecated} annotation on the symbol must also bump the
     * matching {@code DEPRECATED} / {@code DEPRECATED_ANNOTATION} /
     * {@code DEPRECATED_REMOVAL} flag bits, because
     * {@code Check.checkDeprecated} consults those flag bits directly
     * rather than walking the attribute list each time.
     */
    private static long deprecationFlags(List<Attribute.Compound> attrs) {
        long add = 0L;
        for (Attribute.Compound a : attrs) {
            if (a.type == null || a.type.tsym == null) continue;
            if (!"java.lang.Deprecated".contentEquals(a.type.tsym.getQualifiedName())) continue;
            add |= Flags.DEPRECATED | Flags.DEPRECATED_ANNOTATION;
            if (deprecatedForRemoval(a)) {
                add |= Flags.DEPRECATED_REMOVAL;
            }
        }
        return add;
    }

    private static boolean deprecatedForRemoval(Attribute.Compound deprecated) {
        for (var pair : deprecated.values) {
            if (!"forRemoval".contentEquals(pair.fst.name)) continue;
            if (pair.snd instanceof Attribute.Constant ct
                    && ct.value instanceof Boolean b
                    && b) {
                return true;
            }
            // javac stores boolean annotation values as Integer 0/1 too
            if (pair.snd instanceof Attribute.Constant ct
                    && ct.value instanceof Integer i
                    && i != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirrors {@code ClassReader.readInnerClasses}: register each nested type
     * listed on the outer {@link TypeEntry} so qualified nested-type references
     * resolve via {@code outer.members_field}.
     */
    private void registerInnerTypes(ClassSymbol outer, TypeEntry entry, ModuleSymbol module) {
        for (String innerJvm : entry.innerTypeJvmNames()) {
            java.util.List<TypeEntry> candidates = index.getAll(innerJvm);
            if (candidates.isEmpty()) continue;
            TypeEntry innerEntry = classpath.pick(candidates, TypeEntry::sourceUri);
            if (innerEntry == null) continue;

            int dollar = innerJvm.lastIndexOf('$');
            if (dollar < 0) continue;
            String simple = innerJvm.substring(dollar + 1);
            if (simple.isEmpty()) continue;

            ClassSymbol inner = syms.enterClass(module, names.fromString(simple), outer);
            if (inner.owner != outer) continue;

            inner.classfile = new IndexClassFileObject(innerEntry);
            long flags = IndexAccessFlags.classFlags(innerEntry);
            if (isImplicitlyStaticNested(innerEntry)) {
                flags |= STATIC;
            }
            inner.flags_field = flags;

            if ((flags & STATIC) == 0) {
                ClassType innerCt = (ClassType) inner.type;
                innerCt.setEnclosingType(outer.type);
                if (inner.erasure_field != null) {
                    ((ClassType) inner.erasure_field).setEnclosingType(types.erasure(outer.type));
                }
            } else {
                // javac's Symtab.defineClass initialises outer_field to owner.type for
                // every nested type. For static members it must be Type.noType; leaving
                // it as the raw owner causes Attr to reject parameterised uses like
                // Map.Entry<K,V> with "improperly formed type, type arguments given on
                // a raw type".
                ClassType innerCt = (ClassType) inner.type;
                innerCt.setEnclosingType(Type.noType);
                if (inner.erasure_field != null) {
                    ((ClassType) inner.erasure_field).setEnclosingType(Type.noType);
                }
            }

            if ((flags & (Flags.SYNTHETIC | Flags.BRIDGE)) != Flags.SYNTHETIC
                    || inner.name.startsWith(names.lambda)) {
                outer.members_field.enter(inner);
            }
        }
    }

    private static boolean isImplicitlyStaticNested(TypeEntry inner) {
        TypeDeclKind kind = inner.declKind();
        if (kind == TypeDeclKind.INTERFACE
                || kind == TypeDeclKind.ENUM
                || kind == TypeDeclKind.ANNOTATION) {
            return true;
        }
        // Bytecode-derived entries keep declKind UNKNOWN; infer the same
        // implicit-static semantics from access flags.
        long mods = Integer.toUnsignedLong(inner.modifiers());
        return (mods & (Flags.INTERFACE | Flags.ENUM | Flags.ANNOTATION)) != 0;
    }

    /**
     * Ensure a completed indexed class symbol has a stable enclosing type:
     * static members must use {@link Type#noType}; non-static members keep
     * the owning class as enclosing type. This mirrors the invariants javac's
     * ClassReader establishes from real bytecode.
     */
    private void normalizeEnclosingType(ClassSymbol c, TypeEntry entry, ClassType ct) {
        if (!(c.owner instanceof ClassSymbol ownerClass)) return;
        boolean staticMember = (c.flags_field & STATIC) != 0 || isImplicitlyStaticNested(entry);
        if (staticMember) {
            ct.setEnclosingType(Type.noType);
            if (c.erasure_field instanceof ClassType erasureCt) {
                erasureCt.setEnclosingType(Type.noType);
            }
            return;
        }
        ct.setEnclosingType(ownerClass.type);
        if (c.erasure_field instanceof ClassType erasureCt) {
            erasureCt.setEnclosingType(types.erasure(ownerClass.type));
        }
    }

    /**
     * Build javac {@link TypeVar}s for each formal type parameter
     * declared on {@code entry}. Bounds are normalised to
     * {@code java.lang.Object} for now.
     */
    private List<Type> synthesizeTypeParams(ClassSymbol c, TypeEntry entry) {
        if (entry.typeParams().isEmpty()) return List.nil();
        ListBuffer<Type> out = new ListBuffer<>();
        for (TypeParamRef tp : entry.typeParams()) {
            out.add(newTypeVar(tp.name(), c));
        }
        return out.toList();
    }

    private List<Type> synthesizeMethodTypeParams(ClassSymbol owner, MethodEntry entry) {
        if (entry.typeParams().isEmpty()) return List.nil();
        ListBuffer<Type> out = new ListBuffer<>();
        for (TypeParamRef tp : entry.typeParams()) {
            out.add(newTypeVar(tp.name(), owner));
        }
        return out.toList();
    }

    private TypeVar newTypeVar(String name, ClassSymbol owner) {
        TypeVar tv = new TypeVar(names.fromString(name), owner, syms.botType);
        tv.setUpperBound(syms.objectType);
        ((TypeVariableSymbol) tv.tsym).type = tv;
        return tv;
    }

    /**
     * Populate {@code ms.params} from {@link MethodEntry#parameters()}.
     * If the indexer didn't record parameter information (e.g. a class
     * file without a {@code MethodParameters} attribute and no source
     * available), we fall back to the descriptor-derived parameter types
     * and synthesise {@code "arg<i>"} names matching javac's own
     * fallback. Per-parameter declaration annotations from
     * {@code Runtime{Visible,Invisible}ParameterAnnotations} are attached
     * via {@code setDeclarationAttributes}.
     */
    private void populateMethodParameters(MethodSymbol ms, MethodEntry m,
                                          MethodType mt, ModuleSymbol module,
                                          TypeEntry enclosing) {
        com.sun.tools.javac.util.List<Type> jvmParamTypes = mt.getParameterTypes();
        if (jvmParamTypes == null || jvmParamTypes.isEmpty()) {
            ms.params = List.nil();
            return;
        }
        java.util.List<ParameterEntry> indexed = m.parameters();
        ListBuffer<VarSymbol> params = new ListBuffer<>();
        int idx = 0;
        for (Type pt : jvmParamTypes) {
            ParameterEntry pe = idx < indexed.size() ? indexed.get(idx) : null;
            String pName = pe == null || pe.name() == null
                    ? "arg" + idx
                    : pe.name();
            long pflags = Flags.PARAMETER;
            if (pe != null) {
                pflags |= Integer.toUnsignedLong(pe.modifiers());
            }
            VarSymbol psym = new VarSymbol(pflags, names.fromString(pName), pt, ms);
            if (pe != null && !pe.annotations().isEmpty()) {
                psym.setDeclarationAttributes(
                        annotations.toCompounds(pe.annotations(), module, enclosing));
            }
            params.add(psym);
            idx++;
        }
        ms.params = params.toList();
    }

    private MethodSymbol lookupNoArgMethod(ClassSymbol owner, com.sun.tools.javac.util.Name name) {
        for (Symbol s : owner.members().getSymbolsByName(name, sym -> sym.kind == Kinds.Kind.MTH)) {
            if (s instanceof MethodSymbol ms && ms.type.getParameterTypes().isEmpty()) {
                return ms;
            }
        }
        return null;
    }

    /**
     * Coerce an indexer-supplied {@code constantValue} into the boxed
     * representation javac expects on {@code VarSymbol.data}. The
     * indexer already stores boolean/char/byte/short as {@link Integer}
     * (matching {@code ClassReader}'s convention), but a source-side
     * scalar of unexpected shape is conservatively dropped to {@code null}
     * so we never feed {@code VarSymbol.setData} a value javac will
     * reject when constant-folding the use site.
     */
    private static Object coerceConstantValue(Object raw, Type fieldType) {
        if (raw == null) return null;
        return switch (fieldType.getTag()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT ->
                    raw instanceof Integer ? raw : (raw instanceof Number n ? n.intValue() : null);
            case LONG -> raw instanceof Long ? raw : (raw instanceof Number n ? n.longValue() : null);
            case FLOAT -> raw instanceof Float ? raw : (raw instanceof Number n ? n.floatValue() : null);
            case DOUBLE -> raw instanceof Double ? raw : (raw instanceof Number n ? n.doubleValue() : null);
            default -> raw instanceof String ? raw : null;
        };
    }

    /**
     * Second pass over {@code formals} (the TypeVars previously
     * allocated with a placeholder {@code Object} upper bound): resolve
     * each {@link TypeParamRef#bounds()} against the supplied
     * {@code ctx} and call {@link Types#setBounds(TypeVar, List, boolean)}
     * so generic constraints (including F-bounds and intersection types)
     * are enforced at use sites of indexed types.
     */
    private void applyTypeParamBounds(List<Type> formals,
                                      java.util.List<TypeParamRef> refs,
                                      ModuleSymbol module,
                                      ResolutionContext ctx) {
        if (formals.isEmpty() || refs.isEmpty()) return;
        int idx = 0;
        for (Type formal : formals) {
            if (idx >= refs.size()) break;
            TypeParamRef ref = refs.get(idx++);
            if (!(formal instanceof TypeVar tv)) continue;
            if (ref.bounds().isEmpty()) continue;

            ListBuffer<Type> resolved = new ListBuffer<>();
            boolean allInterfaces = !ref.bounds().isEmpty();
            for (ch.castleridge.javals.indexing.model.Type b : ref.bounds()) {
                Type bound = resolveType(b, module, ctx);
                if (bound == null || bound.isErroneous()) {
                    allInterfaces = false;
                    continue;
                }
                resolved.add(bound);
                if (!bound.isInterface()) {
                    allInterfaces = false;
                }
            }
            List<Type> boundsList = resolved.toList();
            if (boundsList.isEmpty()) continue;
            // A single Object bound is the no-op normalisation already
            // applied by TypeParamRef; skip it so we don't unnecessarily
            // wrap the TypeVar in an intersection bound.
            if (boundsList.size() == 1 && boundsList.head.tsym == syms.objectType.tsym) {
                continue;
            }
            types.setBounds(tv, boundsList, allInterfaces);
        }
    }
}
