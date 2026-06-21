package ch.castleridge.javals.javac;

import static com.sun.tools.javac.code.Flags.MODULE;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
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
import com.sun.tools.javac.comp.Annotate.AnnotationTypeMetadata;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Name;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
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

    private TypeEntry pickIndexedType(String jvmName) {
        return classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
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
            try {
                readFromIndex(c, icfo.entry());
            } catch (CompletionFailure ex) {
                completeAsMissing(c);
            }
            return;
        }
        if (c.classfile == null) {
            completeAsMissing(c);
            return;
        }
        try {
            super.readClassFile(c);
        } catch (CompletionFailure ex) {
            completeAsMissing(c);
        }
    }

    /**
     * Finish loading a class symbol that cannot be read from the index or
     * classpath. A {@link CompletionFailure} during completion must not
     * propagate into attribution: javac's {@code Attr.attribTree} catch
     * leaves {@code JCFieldAccess.sym} unset, and JDK 25's
     * {@code isBooleanOrNumeric} then NPEs on {@code Types.memberType}.
     */
    private void completeAsMissing(ClassSymbol c) {
        if (c.members_field == null) {
            c.members_field = WriteableScope.create(c);
        }
        if (c.type == null || !c.type.isErroneous()) {
            c.type = types.createErrorType(c.name, c, c.type != null ? c.type : syms.objectType);
        }
        c.setAnnotationTypeMetadata(AnnotationTypeMetadata.notAnAnnotationType());
    }

    private void readFromIndex(ClassSymbol c, TypeEntry entry) {
        // currentOwner / currentModule are reader-wide mutable state. Resolving a
        // member's type can complete another indexed class (a nested readFromIndex),
        // which would otherwise clobber these fields and leave the rest of this
        // class's members owned by the wrong symbol. Save and restore them so the
        // completion is re-entrancy safe.
        Symbol prevOwner = currentOwner;
        ModuleSymbol prevModule = currentModule;
        try {
            readFromIndexImpl(c, entry);
        } catch (CompletionFailure ex) {
            completeAsMissing(c);
        } finally {
            currentOwner = prevOwner;
            currentModule = prevModule;
        }
    }

    private void readFromIndexImpl(ClassSymbol c, TypeEntry entry) {
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
        long flags = IndexAccessFlags.withDeprecation(
                IndexAccessFlags.classFlags(entry), entry.annotations());
        if ((flags & MODULE) == 0) {
            if (c.owner.kind == Kinds.Kind.PCK || c.owner.kind == Kinds.Kind.ERR) c.flags_field = flags;
        } else {
           throw new UnsupportedOperationException("module info not supported");
        }

        readClassAttrs(c, entry);

        readPermittedSubclasses(c, entry);
        if (!c.getPermittedSubclasses().isEmpty()) {
            c.flags_field |= Flags.SEALED;
        }

        if (ct.supertype_field == null) {
            ct.supertype_field = resolveSupertype(c, entry);
        }
        List<Type> is = List.nil();
        for (ch.castleridge.javals.indexing.model.Type interfaceRef : entry.interfaceRefs()) {
            is = is.prepend(resolveType(interfaceRef, currentModule, entry));
        }
        // An annotation interface implicitly extends java.lang.annotation.Annotation
        // (JLS 9.6). The source indexer records no explicit super-interface for a
        // bare `@interface Foo {}`, so without this the synthesized symbol carries
        // the ACC_ANNOTATION flag but is NOT a subtype of Annotation - and javac's
        // annotation-use check (which verifies the annotation type is assignable to
        // java.lang.annotation.Annotation) then reports "incompatible types: Foo
        // cannot be converted to java.lang.annotation.Annotation" at every use site.
        if ((flags & Flags.ANNOTATION) != 0) {
            Type annotationType = syms.annotationType;
            boolean present = false;
            for (Type i : is) {
                if (i.tsym == annotationType.tsym) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                is = is.prepend(annotationType);
            }
        }
        if (ct.interfaces_field == null)
            ct.interfaces_field = is.reverse();

        // Inner classes must be registered before fields and methods so that
        // resolveParameterized can read each inner ClassSymbol's enclosing type
        // (set here) rather than falling back to Type.noType.  If the enclosing
        // type were noType, javac's isSubtype visitor would eventually call
        // asSuper(t, Type.noType.tsym) with a null sym, causing an NPE in
        // Types.asSuper during override checking (e.g. WindowsFileSystem).
        readInnerClassesFromIndex(c, entry);

        for (FieldEntry field : entry.fields()) {
            enterMember(c, readField(field, entry));
        }
        for (MethodEntry method : entry.methods()) {
            MethodSymbol m = readMethod(method, entry);
            enterMember(c, m);
            // Mirror javac's ClassReader.readMethod, which ORs Flags.DEFAULT
            // onto the owning interface as soon as it reads a default method.
            // Resolve.findMethod only consults an interface for inherited
            // default methods during its DEFAULT_OK phase when the interface
            // symbol itself carries this flag; without it, an inherited
            // default method invoked through a concrete subtype resolves to
            // "cannot find symbol". IndexAccessFlags.methodFlags sets the
            // per-method bit but can only see one method, so the owner-level
            // propagation has to happen here at the call site.
            if ((m.flags_field & Flags.DEFAULT) != 0) {
                c.flags_field |= Flags.DEFAULT;
            }
        }
        readRecordComponents(c, entry);
        enterSyntheticEnumMembersIfNeeded(c, entry);
        enterDefaultConstructorsIfNeeded(c, entry);
        if (c.isRecord()) {
            for (RecordComponent rc: c.getRecordComponents()) {
                rc.accessor = lookupMethod(c, rc.name, List.nil());
            }
        }
        installAnnotationTypeMetadata(c, entry, currentModule);
        typevars = typevars.leave();
    }

    /**
     * Synthesize the two implicitly-declared static members of a
     * source-indexed enum, {@code public static E[] values()} and
     * {@code public static E valueOf(String)}. The compiler generates these
     * for an enum declaration, so source indexing never sees them; without
     * them {@code MyEnum.values()} / {@code MyEnum.valueOf("X")} report
     * "cannot find symbol". Bytecode-indexed enums already carry both methods
     * (read straight from the classfile), so the synthesis is restricted to
     * source entries.
     */
    private void enterSyntheticEnumMembersIfNeeded(ClassSymbol c, TypeEntry entry) {
        if (!entry.isSourceEntry()
                || entry.declKind() != ch.castleridge.javals.indexing.model.TypeDeclKind.ENUM) {
            return;
        }
        Name valuesName = names.fromString("values");
        if (!hasMethodNamed(c, valuesName)) {
            MethodType mt = new MethodType(
                    List.nil(), new ArrayType(c.type, syms.arrayClass), List.nil(), syms.methodClass);
            enterMember(c, new MethodSymbol(Flags.PUBLIC | Flags.STATIC, valuesName, mt, c));
        }
        Name valueOfName = names.fromString("valueOf");
        if (!hasMethodNamed(c, valueOfName)) {
            MethodType mt = new MethodType(
                    List.of(syms.stringType), c.type, List.nil(), syms.methodClass);
            enterMember(c, new MethodSymbol(Flags.PUBLIC | Flags.STATIC, valueOfName, mt, c));
        }
    }

    private boolean hasMethodNamed(ClassSymbol c, Name name) {
        for (Symbol sym : c.members().getSymbolsByName(name)) {
            if (sym.kind == Kinds.Kind.MTH) return true;
        }
        return false;
    }

    /**
     * Compute the supertype of an indexed type.
     *
     * <p>When an explicit supertype was recorded we simply resolve it. The
     * source indexer records only an explicit {@code extends} clause, so a
     * source entry with no recorded super means the supertype is implicit and
     * depends on the declaration kind:
     *
     * <ul>
     *   <li>{@code enum} -> {@code java.lang.Enum<E>}. Without it the
     *   synthesized symbol is not an {@code Enum} subtype, so generic uses
     *   bounded by {@code <E extends Enum<E>>} - {@code EnumSet.of(...)},
     *   {@code EnumMap}, {@code EnumSet.copyOf(...)} - fail with "type
     *   argument E is not within bounds". We rebuild {@code Enum<E>}
     *   explicitly; {@code Comparable<E>} and {@code Serializable} then come
     *   transitively from {@code Enum}.</li>
     *   <li>{@code record} -> {@code java.lang.Record}.</li>
     *   <li>{@code interface}/{@code @interface} -> {@code java.lang.Object}.
     *   An interface has no superclass in the JLS sense, but javac stores
     *   {@code Object} in {@code supertype_field} so that {@code
     *   Types.supertype} and inherited {@code Object} members resolve.</li>
     *   <li>everything else (a plain class) -> {@code java.lang.Object}.</li>
     * </ul>
     *
     * <p>A bytecode entry only reaches the no-recorded-super case for
     * {@code java.lang.Object} itself, which correctly has no supertype.
     */
    private Type resolveSupertype(ClassSymbol c, TypeEntry entry) {
        var superRef = entry.superRef();
        if (superRef != null) {
            return resolveType(superRef, currentModule, entry);
        }
        if (!entry.isSourceEntry()) {
            return Type.noType;
        }
        return switch (entry.declKind()) {
            case ENUM -> {
                ClassSymbol enumSym = resolver.resolveTypeRef(
                        TypeRef.resolved("java/lang/Enum"), currentModule, entry);
                yield enumSym != null
                        ? new ClassType(Type.noType, List.of(c.type), enumSym)
                        : syms.objectType;
            }
            case RECORD -> {
                ClassSymbol recordSym = resolver.resolveTypeRef(
                        TypeRef.resolved("java/lang/Record"), currentModule, entry);
                yield recordSym != null ? recordSym.type : syms.objectType;
            }
            case INTERFACE, ANNOTATION -> syms.objectType;
            default -> syms.objectType;
        };
    }

    /**
     * Source indexing records only explicit constructors; javac normally
     * synthesizes a default constructor and record canonical constructors.
     * Without them, {@code new Nested()} on an indexed nested type fails
     * with "cannot find symbol: constructor".
     */
    private void enterDefaultConstructorsIfNeeded(ClassSymbol c, TypeEntry entry) {
        if (c.isInterface() || c.isEnum()) return;
        if ((c.flags_field & Flags.ANNOTATION) != 0) return;
        if (hasConstructor(c)) return;

        if (c.isRecord()) {
            enterCanonicalRecordConstructor(c, entry);
            return;
        }

        long access = c.flags_field & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
        long constrFlags = access;

        ListBuffer<Type> argtypes = new ListBuffer<>();
        if (isNonStaticNestedClass(c)) {
            ClassSymbol encl = (ClassSymbol) c.owner;
            if (encl.type != null && encl.type.hasTag(TypeTag.CLASS)) {
                argtypes.add(encl.type);
            }
        }

        MethodType mt = new MethodType(argtypes.toList(), syms.voidType, List.nil(), syms.methodClass);
        enterMember(c, new MethodSymbol(constrFlags, names.init, mt, c));
    }

    private void enterCanonicalRecordConstructor(ClassSymbol c, TypeEntry entry) {
        if (entry.recordComponents().isEmpty()) return;

        ListBuffer<Type> argtypes = new ListBuffer<>();
        for (RecordComponentEntry rc : entry.recordComponents()) {
            argtypes.add(resolveType(rc.type(), currentModule, entry));
        }

        long access = c.flags_field & (Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE);
        MethodType mt = new MethodType(argtypes.toList(), syms.voidType, List.nil(), syms.methodClass);
        enterMember(c, new MethodSymbol(access, names.init, mt, c));
    }

    private boolean hasConstructor(ClassSymbol c) {
        for (Symbol sym : c.members().getSymbolsByName(names.init)) {
            if (sym.kind == Kinds.Kind.MTH) return true;
        }
        return false;
    }

    private static boolean isNonStaticNestedClass(ClassSymbol c) {
        return (c.flags_field & Flags.STATIC) == 0 && c.owner.kind == Kinds.Kind.TYP;
    }

    /**
     * Mirror the tail of {@code ClassReader.readClassFile}: indexed
     * annotation types must expose real {@link AnnotationTypeMetadata}
     * so {@code Check.validateAnnotation} can enumerate declared
     * elements. Without this, symbols keep
     * {@link AnnotationTypeMetadata#notAnAnnotationType()} and every
     * use site reports duplicate/missing annotation members (e.g.
     * {@code @SuppressWarnings("unchecked")}).
     */
    private void installAnnotationTypeMetadata(ClassSymbol c, TypeEntry entry, ModuleSymbol module) {
        if ((c.flags_field & Flags.ANNOTATION) != 0) {
            Attribute.Compound target = classAnnotationCompound(entry, "java/lang/annotation/Target", module);
            Attribute.Compound repeatable = classAnnotationCompound(entry, "java/lang/annotation/Repeatable", module);
            c.setAnnotationTypeMetadata(new AnnotationTypeMetadata(c, sym -> {
                if (target != null) sym.getAnnotationTypeMetadata().setTarget(target);
                if (repeatable != null) sym.getAnnotationTypeMetadata().setRepeatable(repeatable);
            }));
        } else {
            c.setAnnotationTypeMetadata(AnnotationTypeMetadata.notAnAnnotationType());
        }
    }

    private Attribute.Compound classAnnotationCompound(TypeEntry entry,
                                                       String jvmBinaryName,
                                                       ModuleSymbol module) {
        for (AnnotationRef ref : entry.annotations()) {
            if (ref.annotationType() instanceof TypeRef.Resolved r
                    && jvmBinaryName.equals(r.jvmBinaryName())) {
                return annotations.toCompound(ref, module, entry);
            }
        }
        return null;
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
            TypeEntry innerEntry = pickIndexedType(innerJvm);
            if (innerEntry == null) {
                // Listed on the outer entry but not on the active classpath:
                // complete now so a later complete() does not throw
                // CompletionFailure and leave method-select trees with sym == null.
                completeAsMissing(member);
                enterMember(c, member);
                continue;
            }
            member.classfile = new IndexClassFileObject(innerEntry);
            long innerFlags = IndexAccessFlags.innerClassFlags(entry, innerEntry);
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
        long flags = IndexAccessFlags.withDeprecation(
                IndexAccessFlags.fieldFlags(entry, field), field.annotations());
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

    /**
     * Resolve the indexed permitted subclasses into member symbols and
     * record them on {@code c}, mirroring the {@code PermittedSubclasses}
     * classfile attribute. {@link ClassSymbol#isPermittedExplicit} must be
     * set so {@code Check} enforces the {@code permits} clause against
     * non-listed subtypes.
     */
    private void readPermittedSubclasses(ClassSymbol c, TypeEntry entry) {
        if (entry.permittedSubclasses().isEmpty()) return;
        ListBuffer<Symbol> permitted = new ListBuffer<>();
        for (TypeRef ref : entry.permittedSubclasses()) {
            ClassSymbol sym = resolver.resolveTypeRef(ref, currentModule, entry);
            if (sym != null) permitted.add(sym);
        }
        c.setPermittedSubclasses(permitted.toList());
        c.isPermittedExplicit = true;
    }

    /**
     * Materialize the {@code Record} attribute: build a
     * {@link RecordComponent} per indexed component so consumers walking
     * {@code ClassSymbol.getRecordComponents()} (and javac's own record
     * checks) see them. Accessor wiring happens at the call site once the
     * synthesized accessor methods have been entered.
     */
    private void readRecordComponents(ClassSymbol c, TypeEntry entry) {
        if (entry.recordComponents().isEmpty()) return;
        ListBuffer<RecordComponent> components = new ListBuffer<>();
        for (RecordComponentEntry rc : entry.recordComponents()) {
            Type type = resolveType(rc.type(), currentModule, entry);
            RecordComponent component = new RecordComponent(names.fromString(rc.name()), type, c);
            component.setDeclarationAttributes(
                    annotations.toCompounds(rc.annotations(), currentModule, entry));
            components.add(component);
        }
        c.setRecordComponents(components.toList());
    }

    private MethodSymbol readMethod(MethodEntry method, TypeEntry entry) {
        try {
            typevars = typevars.dup();
            List<Type> methodTypeParams = enterTypeParams(method.typeParams(), currentOwner, currentModule, entry);
            long flags = IndexAccessFlags.withDeprecation(
                    IndexAccessFlags.methodFlags(entry, method), method.annotations());
            Name name = names.fromString(method.name());
            MethodType methodType = resolveMethodType(method, currentModule, entry);
            Type sig = methodTypeParams.isEmpty()
                    ? methodType
                    : new ForAll(methodTypeParams, methodType);
            MethodSymbol m = new MethodSymbol(flags, name, sig, currentOwner);
            m.setDeclarationAttributes(annotations.toCompounds(method.annotations(), currentModule, entry));
            m.params = readParameters(method, m, currentModule, entry);
            if (method.annotationDefault() != null) {
                m.defaultValue = annotations.toAttribute(
                        method.annotationDefault(), m.getReturnType(), currentModule, entry);
            }
            return m;
        } finally {
            typevars = typevars.leave();
        }
    }

    /**
     * Build the {@link MethodSymbol#params} list from the indexed
     * {@link ParameterEntry}s so that downstream consumers see real
     * parameter names, modifiers ({@code final}) and declaration
     * annotations instead of javac's synthesized {@code arg0}/{@code arg1}
     * fallback. Each {@link VarSymbol} is created with the
     * {@link Flags#PARAMETER} bit set and owned by {@code m}.
     */
    private List<VarSymbol> readParameters(MethodEntry method, MethodSymbol m,
                                           ModuleSymbol module, TypeEntry entry) {
        if (method.parameters().isEmpty()) return List.nil();
        ListBuffer<VarSymbol> params = new ListBuffer<>();
        int index = 0;
        for (ParameterEntry p : method.parameters()) {
            long flags = Integer.toUnsignedLong(p.modifiers()) | Flags.PARAMETER;
            String pname = p.name() != null ? p.name() : "arg" + index;
            Name name = names.fromString(pname);
            Type type = resolveType(p.type(), module, entry);
            VarSymbol v = new VarSymbol(flags, name, type, m);
            v.setDeclarationAttributes(annotations.toCompounds(p.annotations(), module, entry));
            params.add(v);
            index++;
        }
        return params.toList();
    }

    private MethodType resolveMethodType(MethodEntry m, ModuleSymbol module, TypeEntry entry) {
        ListBuffer<Type> params = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type pr : m.paramTypes()) {
            params.add(resolveType(pr, module, entry));
        }
        ListBuffer<Type> thrown = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type tr : m.throwsTypes()) {
            Type thrownType = resolveType(tr, module, entry);
            // Mirror javac's ClassReader: a type variable that appears in the
            // throws clause must be flagged THROWS. This flag is the sole gate
            // for the JLS inference rule that solves an otherwise-unconstrained
            // throws type variable to RuntimeException. Without it the "sneaky
            // throws" idiom `<E extends Throwable> void m() throws E` is seen by
            // callers as `throws Throwable`, yielding spurious "unreported
            // exception java.lang.Throwable" diagnostics.
            if (thrownType.hasTag(TypeTag.TYPEVAR)) {
                thrownType.tsym.flags_field |= Flags.THROWS;
            }
            thrown.add(thrownType);
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
            ClassSymbol symbol = resolver.resolveTypeRef(tr, module, entry);
            if (symbol != null) {
                // Bare class references (raw types) must be erased. Returning
                // symbol.type for a generic declaration keeps its formal type
                // parameters (e.g. NetworkMetrics<T>), which breaks override
                // checks against source uses of the raw form (NetworkMetrics) or
                // raw type arguments (ConcurrentCyclicSequence<HandlerHolder>).
                return types.erasure(symbol.type);
            }
        }
        return syms.errType;
    }

    private Type lookupTypeVar(String name) {
        Symbol sym = typevars.findFirst(names.fromString(name));
        if (sym == null) return syms.objectType;
        return sym.type;
    }

    private Type resolveParameterized(Parameterized p, ModuleSymbol module, TypeEntry entry) {
        ClassSymbol raw = resolver.resolveTypeRef(p.raw(), module, entry);
        if (raw == null) return syms.errType;
        ListBuffer<Type> args = new ListBuffer<>();
        for (ch.castleridge.javals.indexing.model.Type arg : p.typeArgs()) {
            args.add(resolveType(arg, module, entry));
        }
        if (raw instanceof ClassSymbol ct) {
            // Static nested types must not carry the raw outer type: propagating it
            // causes Attr to reject uses like Map.Entry<K,V> with "improperly formed
            // type, type arguments given on a raw type". Top-level types already have
            // outer_field == Type.noType, so this is only a correction for static
            // members whose outer_field was initialised to the raw owner by
            // Symtab.defineClass.
            Type outer = (ct.flags_field & Flags.STATIC) != 0
                    ? Type.noType
                    : ct.type.getEnclosingType();
            if (outer == Type.noType
                    && (ct.flags_field & Flags.STATIC) == 0
                    && ct.owner instanceof ClassSymbol owner
                    && owner.type != null
                    && !owner.type.isErroneous()) {
                outer = owner.type;
            }
            return new ClassType(outer, args.toList(), ct);
        }
        return raw.type;
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
