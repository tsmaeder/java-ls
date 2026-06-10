package ch.castleridge.javals.javac;

import static com.sun.tools.javac.code.Flags.ACC_MODULE;
import static com.sun.tools.javac.code.Flags.ACC_SUPER;
import static com.sun.tools.javac.code.Flags.MODULE;
import static com.sun.tools.javac.code.Flags.STATIC;

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
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
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
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;

public final class IndexClassReader2 extends ClassReader {

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

    private IndexClassReader2(Context context, Index index, ClasspathOrder classpath) {
        super(context);
        this.index = index;
        this.classpath = classpath;
        this.syms = Symtab.instance(context);
        this.names = Names.instance(context);
        this.types = Types.instance(context);
        this.annotations = new IndexAnnotations(syms, names, types);
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
        context.put(classReaderKey, (Context.Factory<ClassReader>) ctx -> new IndexClassReader2(ctx, index, classpath));
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
        ClassType ct = (ClassType)c.type;

        // allocate scope for members
        c.members_field = WriteableScope.create(c);

        // prepare type variable table
        typevars = typevars.dup(currentOwner);
        if (ct.getEnclosingType().hasTag(TypeTag.CLASS))
            enterTypevars(c.owner, ct.getEnclosingType());

        // read flags, or skip if this is an inner class
        long flags = IndexAccessFlags.classFlags(entry);
        if ((flags & MODULE) == 0) {
            if (c.owner.kind == Kinds.Kind.PCK || c.owner.kind == Kinds.Kind.ERR) c.flags_field = flags;
            // read own class name and check that it matches
            currentModule = c.packge().modle;
        } else {
           throw new UnsupportedOperationException("module info not supported");
        }

        readClassAttrs(c, entry);

        if (!c.getPermittedSubclasses().isEmpty()) {
            c.flags_field |= Flags.SEALED;
        }

        if (ct.supertype_field == null) {
            Type superType = resolver.resolve(entry.superRef(), currentModule, entry);
            ct.supertype_field = superType;
        }
        List<Type> is = List.nil();
        for (TypeRef interfaceRef : entry.interfaceRefs()) {
            Type interfaceType = resolver.resolve(interfaceRef, currentModule, entry);
            is = is.prepend(interfaceType);
        }
        if (ct.interfaces_field == null)
            ct.interfaces_field = is.reverse();

        for (FieldEntry field : entry.fields()) {
            enterMember(c, readField(field, entry));
        }
        for (MethodEntry method : entry.methods()) {
            enterMember(c, readMethod(method));
        }
        if (c.isRecord()) {
            for (RecordComponent rc: c.getRecordComponents()) {
                rc.accessor = lookupMethod(c, rc.name, List.nil());
            }
        }
        typevars = typevars.leave();
    }

    private void readClassAttrs(ClassSymbol c, TypeEntry entry) {
        for (AnnotationRef annotation : entry.annotations()) {
            c.setDeclarationAttributes(annotations.toCompounds(annotation, currentModule));
        }
    }

}
