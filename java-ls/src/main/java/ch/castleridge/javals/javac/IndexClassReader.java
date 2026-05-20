package ch.castleridge.javals.javac;

import static com.sun.tools.javac.code.Flags.STATIC;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
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
 * {@link TypeRefResolver} to turn the {@link TypeRef}s emitted by the
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
        this.annotations = new IndexAnnotations(syms, names, types);
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
        List<Type> classTypeParams = synthesizeTypeParams(c, entry);
        ct.typarams_field = classTypeParams;
        TypeRefResolver.ResolutionContext classCtx =
                TypeRefResolver.ResolutionContext.of(entry, classTypeParams);

        ct.supertype_field = entry.superRef() == null
                ? Type.noType
                : resolver.resolve(entry.superRef(), module, classCtx);

        List<Type> interfaces = List.nil();
        for (TypeRef iref : entry.interfaceRefs()) {
            interfaces = interfaces.prepend(resolver.resolve(iref, module, classCtx));
        }
        ct.interfaces_field = interfaces.reverse();

        for (FieldEntry f : entry.fields()) {
            Type t = resolver.resolveField(f, module, classCtx);
            VarSymbol v = new VarSymbol(IndexAccessFlags.fieldFlags(entry, f), names.fromString(f.name()), t, c);
            List<Attribute.Compound> fAttrs = annotations.toCompounds(f.annotations(), module);
            v.setDeclarationAttributes(fAttrs);
            v.flags_field |= deprecationFlags(fAttrs);
            c.members_field.enter(v);
        }

        for (MethodEntry m : entry.methods()) {
            List<Type> methodTypeParams = synthesizeMethodTypeParams(c, m);
            TypeRefResolver.ResolutionContext methodCtx =
                    TypeRefResolver.ResolutionContext.of(entry, classTypeParams, methodTypeParams);
            MethodType mt = resolver.resolveMethod(m, module, methodCtx);
            Type methodType = methodTypeParams.isEmpty()
                    ? mt
                    : new Type.ForAll(methodTypeParams, mt);
            MethodSymbol ms = new MethodSymbol(IndexAccessFlags.methodFlags(entry, m), names.fromString(m.name()), methodType, c);
            List<Attribute.Compound> mAttrs = annotations.toCompounds(m.annotations(), module);
            ms.setDeclarationAttributes(mAttrs);
            ms.flags_field |= deprecationFlags(mAttrs);
            if (m.annotationDefault() != null) {
                Attribute defaultAttr = annotations.toAttribute(m.annotationDefault(), mt.getReturnType(), module);
                ms.defaultValue = defaultAttr != null ? defaultAttr : ANNOTATION_DEFAULT_SENTINEL;
            }
            c.members_field.enter(ms);
        }

        // Class-level annotations must be attached before the
        // AnnotationTypeMetadata is built so the metadata can pick up
        // @Target and @Repeatable from the same compounds.
        List<Attribute.Compound> classAttrs = annotations.toCompounds(entry.annotations(), module);
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
            }

            if ((flags & (Flags.SYNTHETIC | Flags.BRIDGE)) != Flags.SYNTHETIC
                    || inner.name.startsWith(names.lambda)) {
                outer.members_field.enter(inner);
            }
        }
    }

    private static boolean isImplicitlyStaticNested(TypeEntry inner) {
        TypeDeclKind kind = inner.declKind();
        return kind == TypeDeclKind.INTERFACE
                || kind == TypeDeclKind.ENUM
                || kind == TypeDeclKind.ANNOTATION;
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
}
