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
     * Placeholder default value attached to {@link MethodSymbol#defaultValue}
     * for annotation elements that have a {@code default} clause (or
     * {@code AnnotationDefault} attribute in bytecode). The actual value
     * is not preserved by the index because {@code Check.validateAnnotation}
     * only checks for presence ({@code != null}); using {@link Attribute.Error}
     * also gives a sensible "unknown value" mirror for any reflective access.
     */
    private static final Attribute ANNOTATION_DEFAULT_SENTINEL = new Attribute.Error(Type.noType);

    private final Index index;
    private final ClasspathOrder classpath;
    private final Symtab syms;
    private final Names names;
    private final Types types;
    private final TypeRefResolver resolver;

    private IndexClassReader(Context context, Index index, ClasspathOrder classpath) {
        super(context);
        this.index = index;
        this.classpath = classpath;
        this.syms = Symtab.instance(context);
        this.names = Names.instance(context);
        this.types = Types.instance(context);
        this.resolver = new TypeRefResolver(syms, names, index, classpath);
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
            if (m.hasAnnotationDefault()) {
                // Only the presence matters for Check.validateAnnotation;
                // a sentinel non-null Attribute is enough to mark the
                // element as having a default.
                ms.defaultValue = ANNOTATION_DEFAULT_SENTINEL;
            }
            c.members_field.enter(ms);
        }

        // Mirror ClassReader.readClassFile: annotation types need a real
        // AnnotationTypeMetadata so Check.validateAnnotation can enumerate
        // their element methods. Without this the class keeps the default
        // notAnAnnotationType() metadata, which exposes an empty element
        // set and makes every supplied argument look like a duplicate.
        if ((c.flags_field & Flags.ANNOTATION) != 0) {
            c.setAnnotationTypeMetadata(new AnnotationTypeMetadata(c, null));
        }

        c.completer = Symbol.Completer.NULL_COMPLETER;
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
