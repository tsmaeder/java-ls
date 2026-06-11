package ch.castleridge.javals.javac;

import static com.sun.tools.javac.code.Flags.MODULE;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symbol.RecordComponent;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.ForAll;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Name;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.Type.Annotated;
import ch.castleridge.javals.indexing.model.Type.Array;
import ch.castleridge.javals.indexing.model.Type.Parameterized;
import ch.castleridge.javals.indexing.model.Type.Primitive;
import ch.castleridge.javals.indexing.model.Type.TypeVariable;
import ch.castleridge.javals.indexing.model.Type.Wildcard;


public final class IndexClassReader extends ClassReader {
    private final Symtab syms;
    private final Names names;
    private final Types types;
    private final Index index;
    private final ClasspathOrder classpath;
    private final TypeRefResolver resolver;
    private final IndexAnnotations annotations;

    private IndexClassReader(Context context, Index index, ClasspathOrder classpath) {
        super(context);
        this.syms = Symtab.instance(context);
        this.names = Names.instance(context);
        this.types = Types.instance(context);
        this.index = index;
        this.classpath = classpath;
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
            readFromIndex(c, icfo.entry());
            return;
        }
        super.readClassFile(c);
    }

    private void readFromIndex(ClassSymbol c, TypeEntry entry) {
        currentOwner = c;
        currentModule = c.packge() == null ? syms.unnamedModule : c.packge().modle;
        if (currentModule == null) currentModule = syms.unnamedModule;
        ClassType ct = (ClassType)c.type;

        // allocate scope for members
        c.members_field = WriteableScope.create(c);

        // prepare type variable table
        typevars = typevars.dup(currentOwner);
        if (ct.getEnclosingType().hasTag(TypeTag.CLASS))
            enterTypevars(c.owner, ct.getEnclosingType());

        List<Type> classTypeParams = enterTypeParams(entry.typeParams(), c, currentModule, entry);
        ct.typarams_field = classTypeParams;

        // read flags, or skip if this is an inner class
        long flags = IndexAccessFlags.classFlags(entry);
        if ((flags & MODULE) == 0) {
            if (c.owner.kind == Kinds.Kind.PCK || c.owner.kind == Kinds.Kind.ERR) c.flags_field = flags;
        } else {
           throw new UnsupportedOperationException("module info not supported");
        }

        readClassAttrs(c, entry);

        if (!c.getPermittedSubclasses().isEmpty()) {
            c.flags_field |= Flags.SEALED;
        }

        if (ct.supertype_field == null) {
            if (entry.superRef() instanceof TypeRef r) {
                Type superType = resolver.resolve(r, currentModule, entry);
                ct.supertype_field = superType;  
            } else {
                ct.supertype_field = syms.errType;
            }
        }
        List<Type> is = List.nil();
        for (ch.castleridge.javals.indexing.model.Type interfaceRef : entry.interfaceRefs()) {
            if (interfaceRef instanceof TypeRef r) {
                Type interfaceType = resolver.resolve(r, currentModule, entry);
                is = is.prepend(interfaceType);
            } else {
                is = is.prepend(syms.errType);
            }
        }
        if (ct.interfaces_field == null)
            ct.interfaces_field = is.reverse();

        for (FieldEntry field : entry.fields()) {
            enterMember(c, readField(field, entry));
        }
        for (MethodEntry method : entry.methods()) {
            enterMember(c, readMethod(method, entry));
        }
        readInnerClassesFromIndex(c, entry);
        if (c.isRecord()) {
            for (RecordComponent rc: c.getRecordComponents()) {
                rc.accessor = lookupMethod(c, rc.name, List.nil());
            }
        }
        typevars = typevars.leave();
    }

    /**
     * Mirror javac's {@code readInnerClasses}: register each indexed nested
     * type as a member of {@code c} so qualified names ({@code Map.Entry})
     * and single-type imports of nested types ({@code Base64.Encoder})
     * resolve against the outer symbol's scope.
     */
    private void readInnerClassesFromIndex(ClassSymbol c, TypeEntry entry) {
        for (String innerJvm : entry.innerTypeJvmNames()) {
            int dollar = innerJvm.lastIndexOf('$');
            if (dollar < 0 || dollar == innerJvm.length() - 1) continue;
            Name innerName = names.fromString(innerJvm.substring(dollar + 1));
            ClassSymbol member = enterClass(innerName, c);
            TypeEntry innerEntry = classpath.pick(index.getAll(innerJvm), TypeEntry::sourceUri);
            long innerFlags = innerEntry != null
                    ? IndexAccessFlags.classFlags(innerEntry)
                    : member.flags_field;
            if ((innerFlags & Flags.STATIC) == 0) {
                ClassType memberType = (ClassType) member.type;
                memberType.setEnclosingType(c.type);
                if (member.erasure_field != null) {
                    ((ClassType) member.erasure_field).setEnclosingType(types.erasure(c.type));
                }
            }
            member.flags_field = innerFlags;
            enterMember(c, member);
        }
    }

        private MethodSymbol lookupMethod(ClassSymbol c, Name name, List<Type> argtypes) {
        for (Symbol s : c.members().getSymbolsByName(name, sym -> sym.kind == Kinds.Kind.MTH)) {
            if (types.isSameTypes(s.type.getParameterTypes(), argtypes)) {
                return (MethodSymbol) s;
            }
        }
        return null;
    }

    /**
     * Add member to class unless it is synthetic.
     */
    private void enterMember(ClassSymbol c, Symbol sym) {
        // Synthetic members are not entered -- reason lost to history (optimization?).
        // Lambda methods must be entered because they may have inner classes (which reference them)
        if ((sym.flags_field & (Flags.SYNTHETIC|Flags.BRIDGE)) != Flags.SYNTHETIC || sym.name.startsWith(names.lambda))
            c.members_field.enter(sym);
    }

    private VarSymbol readField(FieldEntry field, TypeEntry entry) {
        long flags = IndexAccessFlags.fieldFlags(entry, field);
        Name name = names.fromString(field.name());
        Type type = resolveType(field.type(), currentModule, entry);
        VarSymbol v = new VarSymbol(flags, name, type, currentOwner);
        v.setDeclarationAttributes(annotations.toCompounds(field.annotations(), currentModule, entry));
        if (field.constantValue() != null && (v.flags_field & Flags.FINAL) != 0) {
            v.setData(field.constantValue());
        }
        return v;
    }

    private void readClassAttrs(ClassSymbol c, TypeEntry entry) {
        c.setDeclarationAttributes(annotations.toCompounds(entry.annotations(), currentModule, entry));
    }

    private MethodSymbol readMethod(MethodEntry method, TypeEntry entry) {
        try {
            typevars = typevars.dup();
            List<Type> methodTypeParams = enterTypeParams(method.typeParams(), currentOwner, currentModule, entry);
            long flags = IndexAccessFlags.methodFlags(entry, method);
            Name name = names.fromString(method.name());
            MethodType methodType = resolveMethodType(method, currentModule, entry);
            Type sig = methodTypeParams.isEmpty()
                    ? methodType
                    : new ForAll(methodTypeParams, methodType);
            MethodSymbol m = new MethodSymbol(flags, name, sig, currentOwner);
            m.setDeclarationAttributes(annotations.toCompounds(method.annotations(), currentModule, entry));
            return m;
        } finally {
            typevars = typevars.leave();
        }
    }

    private MethodType resolveMethodType(MethodEntry m, ModuleSymbol module, TypeEntry entry) {
        ListBuffer<Type> params = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type pr : m.paramTypes()) {
            params.add(resolveType(pr, module, entry));
        }
        ListBuffer<Type> thrown = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type tr : m.throwsTypes()) {
            thrown.add(resolveType(tr, module, entry));
        }
        Type ret = resolveType(m.returnType(), module, entry);
        return new MethodType(params.toList(), ret, thrown.toList(), syms.methodClass);
    }


 /**
     * Resolve an indexed {@link ch.castleridge.javals.indexing.model.Type}
     * tree into a javac {@link Type}. Structural shapes are handled here;
     * the class-reference leaves ({@link TypeRef}) are delegated to
     * {@link TypeRefResolver}.
     */
    private Type resolveType(ch.castleridge.javals.indexing.model.Type ref,
                             ModuleSymbol module, TypeEntry entry) {
        if (ref == null) return syms.errType;
        if (ref instanceof Annotated annotated) {
            Type inner = resolveType(annotated.inner(), module, entry);
            List<Attribute.TypeCompound> compounds =
                    annotations.toTypeCompounds(annotated.annotations(), module, entry);
            if (compounds.isEmpty()) return inner;
            return inner.annotatedType(compounds);
        }
        if (ref instanceof Primitive p) return primitive(p);
        if (ref instanceof Array a) {
            return new ArrayType(resolveType(a.element(), module, entry), syms.arrayClass);
        }
        if (ref instanceof TypeVariable tv) {
            return lookupTypeVar(tv.name());
        }
        if (ref instanceof Wildcard w) {
            return resolveWildcard(w, module, entry);
        }
        if (ref instanceof Parameterized p) {
            return resolveParameterized(p, module, entry);
        }
        if (ref instanceof TypeRef tr) {
            return resolver.resolve(tr, module, entry);
        }
        return syms.errType;
    }

    private Type lookupTypeVar(String name) {
        Symbol sym = typevars.findFirst(names.fromString(name));
        if (sym == null) return syms.objectType;
        return sym.type;
    }

    private Type resolveParameterized(Parameterized p, ModuleSymbol module, TypeEntry entry) {
        Type raw = resolver.resolve(p.raw(), module, entry);
        ListBuffer<Type> args = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type arg : p.typeArgs()) {
            args.add(resolveType(arg, module, entry));
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

    private Type resolveWildcard(Wildcard w, ModuleSymbol module, TypeEntry entry) {
        return switch (w.kind()) {
            case UNBOUNDED -> new WildcardType(syms.objectType, BoundKind.UNBOUND, syms.boundClass);
            case EXTENDS -> new WildcardType(
                    resolveType(w.bound(), module, entry), BoundKind.EXTENDS, syms.boundClass);
            case SUPER -> new WildcardType(
                    resolveType(w.bound(), module, entry), BoundKind.SUPER, syms.boundClass);
        };
    }

    /**
     * Build javac {@link TypeVar}s for each formal type parameter,
     * enter them into the {@code typevars} scope, and resolve their
     * declared bounds. Two internal passes are required so forward
     * references (e.g. {@code <T extends Comparable<U>, U>}) resolve
     * through {@link #lookupTypeVar(String)}.
     */
    private List<Type> enterTypeParams(java.util.List<TypeParamRef> refs,
                                       Symbol owner,
                                       ModuleSymbol module,
                                       TypeEntry entry) {
        if (refs.isEmpty()) return List.nil();
        ListBuffer<Type> out = new ListBuffer<>();
        for (TypeParamRef tp : refs) {
            TypeVar tv = newTypeVar(tp.name(), owner);
            typevars.enter(tv.tsym);
            out.add(tv);
        }
        List<Type> formals = out.toList();
        int idx = 0;
        for (Type formal : formals) {
            TypeParamRef ref = refs.get(idx++);
            if (!(formal instanceof TypeVar tv)) continue;
            if (ref.bounds().isEmpty()) continue;

            ListBuffer<Type> resolved = new ListBuffer<>();
            boolean allInterfaces = true;
            for (ch.castleridge.javals.indexing.model.Type b : ref.bounds()) {
                Type bound = resolveType(b, module, entry);
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
            if (boundsList.size() == 1 && boundsList.head.tsym == syms.objectType.tsym) {
                continue;
            }
            types.setBounds(tv, boundsList, allInterfaces);
        }
        return formals;
    }

    private TypeVar newTypeVar(String name, Symbol owner) {
        TypeVar tv = new TypeVar(names.fromString(name), owner, syms.botType);
        tv.setUpperBound(syms.objectType);
        ((TypeVariableSymbol) tv.tsym).type = tv;
        return tv;
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
}
