package ch.castleridge.javals.javac;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Resolves {@link TypeRef} instances - produced by the source and bytecode
 * indexers - into javac {@link Type} instances.
 */
final class TypeRefResolver {

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

    private final Symtab syms;
    private final Names names;
    private final Index index;
    private final ClasspathOrder classpath;

    TypeRefResolver(Symtab syms, Names names, Index index, ClasspathOrder classpath) {
        this.syms = syms;
        this.names = names;
        this.index = index;
        this.classpath = classpath;
    }

    Type resolve(TypeRef ref, ModuleSymbol module, TypeEntry enclosing) {
        return resolve(ref, module, ResolutionContext.of(enclosing, List.nil()));
    }

    Type resolve(TypeRef ref, ModuleSymbol module, ResolutionContext ctx) {
        if (ref == null) return syms.errType;
        if (ref instanceof TypeRef.Primitive p) return primitive(p);
        if (ref instanceof TypeRef.Array a) {
            return new ArrayType(resolve(a.element(), module, ctx), syms.arrayClass);
        }
        if (ref instanceof TypeRef.TypeVariable tv) {
            return lookupTypeVar(tv.name(), ctx);
        }
        if (ref instanceof TypeRef.Wildcard w) {
            return resolveWildcard(w, module, ctx);
        }
        if (ref instanceof TypeRef.Parameterized p) {
            return resolveParameterized(p, module, ctx);
        }
        if (ref instanceof TypeRef.Resolved r) {
            return classType(module, r.jvmBinaryName());
        }
        if (ref instanceof TypeRef.Unresolved u) {
            return classType(module, resolveSimple(u.simpleName(), ctx.enclosing()));
        }
        return syms.errType;
    }

    MethodType resolveMethod(MethodEntry m, ModuleSymbol module, ResolutionContext ctx) {
        ListBuffer<Type> params = new ListBuffer<>();
        for (TypeRef pr : m.paramTypes()) {
            params.add(resolve(pr, module, ctx));
        }
        ListBuffer<Type> thrown = new ListBuffer<>();
        for (TypeRef tr : m.throwsTypes()) {
            thrown.add(resolve(tr, module, ctx));
        }
        Type ret = resolve(m.returnType(), module, ctx);
        return new MethodType(params.toList(), ret, thrown.toList(), syms.methodClass);
    }

    Type resolveField(FieldEntry f, ModuleSymbol module, ResolutionContext ctx) {
        return resolve(f.type(), module, ctx);
    }

    Type classType(ModuleSymbol module, String jvmBinaryName) {
        if (jvmBinaryName == null || jvmBinaryName.isEmpty()) return syms.errType;
        String dotted = jvmBinaryName.replace('/', '.');
        ClassSymbol sym = syms.enterClass(module, names.fromString(dotted));
        return sym.type;
    }

    private Type resolveParameterized(TypeRef.Parameterized p, ModuleSymbol module, ResolutionContext ctx) {
        Type raw = resolve(p.raw(), module, ctx);
        ListBuffer<Type> args = new ListBuffer<>();
        for (TypeRef arg : p.typeArgs()) {
            args.add(resolve(arg, module, ctx));
        }
        if (raw instanceof ClassType ct) {
            return new ClassType(ct.getEnclosingType(), args.toList(), ct.tsym);
        }
        return raw;
    }

    private Type resolveWildcard(TypeRef.Wildcard w, ModuleSymbol module, ResolutionContext ctx) {
        return switch (w.kind()) {
            case UNBOUNDED -> new WildcardType(syms.objectType, BoundKind.UNBOUND, syms.boundClass);
            case EXTENDS -> new WildcardType(
                    resolve(w.bound(), module, ctx), BoundKind.EXTENDS, syms.boundClass);
            case SUPER -> new WildcardType(
                    resolve(w.bound(), module, ctx), BoundKind.SUPER, syms.boundClass);
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

    private Type primitive(TypeRef.Primitive p) {
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

    private String resolveSimple(String simple, TypeEntry enclosing) {
        if (enclosing == null) {
            return "java/lang/" + simple;
        }

        for (String nested : enclosing.innerTypeJvmNames()) {
            int dollar = nested.lastIndexOf('$');
            int slash = nested.lastIndexOf('/');
            int start = Math.max(dollar, slash) + 1;
            if (nested.substring(start).equals(simple)) {
                return nested;
            }
        }

        SourceResolutionHints hints = enclosing.hints();
        if (hints == null) {
            return "java/lang/" + simple;
        }

        if (hints.siblingSimpleNames().contains(simple)) {
            String pkg = hints.sourcePackage();
            return pkg.isEmpty() ? simple : pkg + "/" + simple;
        }

        String single = hints.singleTypeImports().get(simple);
        if (single != null) return single;

        String pkg = hints.sourcePackage();
        String samePkg = pkg.isEmpty() ? simple : pkg + "/" + simple;
        if (availableOnClasspath(samePkg)) return samePkg;

        for (String od : hints.onDemandImports()) {
            String candidate = od.isEmpty() ? simple : od + "/" + simple;
            if (availableOnClasspath(candidate)) return candidate;
        }

        return "java/lang/" + simple;
    }

    private boolean availableOnClasspath(String jvmName) {
        return classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri) != null;
    }
}
