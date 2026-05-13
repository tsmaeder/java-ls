package ch.castleridge.javals.javac;

import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;
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

    private final Index index;
    private final ClasspathOrder classpath;
    private final Symtab syms;
    private final Names names;
    private final TypeRefResolver resolver;

    private IndexClassReader(Context context, Index index, ClasspathOrder classpath) {
        super(context);
        this.index = index;
        this.classpath = classpath;
        this.syms = Symtab.instance(context);
        this.names = Names.instance(context);
        Types.instance(context); // touch to ensure the service is materialized
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

        c.flags_field = entry.accessFlags();
        c.members_field = WriteableScope.create(c);

        ClassType ct = (ClassType) c.type;
        if (c.name().toString().equals("java/lang/Object")) {
            System.err.println("superRef: " + entry.superRef());
        }
        ct.supertype_field = entry.superRef() == null
                ? Type.noType
                : resolver.resolve(entry.superRef(), module, entry);

        List<Type> interfaces = List.nil();
        for (TypeRef iref : entry.interfaceRefs()) {
            interfaces = interfaces.prepend(resolver.resolve(iref, module, entry));
        }
        ct.interfaces_field = interfaces.reverse();
        ct.typarams_field = List.nil();

        for (FieldEntry f : entry.fields()) {
            Type t = resolver.resolveField(f, module, entry);
            VarSymbol v = new VarSymbol(f.accessFlags(), names.fromString(f.name()), t, c);
            c.members_field.enter(v);
        }

        for (MethodEntry m : entry.methods()) {
            MethodType mt = resolver.resolveMethod(m, module, entry);
            MethodSymbol ms = new MethodSymbol(m.accessFlags(), names.fromString(m.name()), mt, c);
            c.members_field.enter(ms);
        }

        c.completer = Symbol.Completer.NULL_COMPLETER;
    }
}
