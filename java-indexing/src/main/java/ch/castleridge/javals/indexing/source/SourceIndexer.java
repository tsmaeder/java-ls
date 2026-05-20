package ch.castleridge.javals.indexing.source;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.objectweb.asm.Opcodes;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;

import ch.castleridge.javals.indexing.index.InMemorySource;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.intern.Interner;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Parses a single Java source file and emits {@link TypeEntry} records for
 * every declared type (nested types included).
 *
 * <p>We do <em>no</em> cross-file name resolution here: every reference that
 * cannot be decided from the compilation unit alone (identifier that is not
 * fully-qualified via {@code MemberSelectTree} and does not match a
 * single-type import) is emitted as {@link TypeRef.Unresolved}. Final
 * resolution happens later in the index class reader, which has access to
 * the full {@link Index} and the per-CU
 * {@link SourceResolutionHints} we attach to every emitted
 * {@link TypeEntry}.
 *
 * <p>Type parameters declared by an enclosing class or the current method
 * are treated as erasure-to-{@code java.lang.Object}; that is sufficient
 * for symbol population at the erasure level, which is what
 * {@code ClassReader} needs.
 */
public final class SourceIndexer {

    private SourceIndexer() {}

    public static void index(URI uri, URI sourceUri, CharSequence content, Index into) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject input = new InMemorySource(uri, content);
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, d -> {}, List.of(), List.of(), List.of(input));
        String resourceUriStr = uri == null ? null : uri.toString();
        String sourceUriStr = sourceUri == null ? null : Interner.intern(sourceUri.toString());
        try {
            for (CompilationUnitTree cu : task.parse()) {
                indexCompilationUnit(resourceUriStr, sourceUriStr, cu, into);
            }
        } catch (IOException e) {
            // Parsing never actually does I/O beyond our in-memory source; treat
            // as empty input and move on.
        }
    }

    private static void indexCompilationUnit(String uri, String sourceUri, CompilationUnitTree cu, Index into) {
        String packageName = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        String packageJvm = Interner.intern(packageName.replace('.', '/'));

        Map<String, String> singleTypeImports = new HashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        for (ImportTree imp : cu.getImports()) {
            if (imp.isStatic()) continue;
            String qn = imp.getQualifiedIdentifier().toString();
            if (qn.endsWith(".*")) {
                String pkg = qn.substring(0, qn.length() - 2).replace('.', '/');
                onDemandImports.add(pkg);
                continue;
            }
            int dot = qn.lastIndexOf('.');
            String simple = dot < 0 ? qn : qn.substring(dot + 1);
            singleTypeImports.put(simple, qn.replace('.', '/'));
        }

        Set<String> siblings = new LinkedHashSet<>();
        for (Tree t : cu.getTypeDecls()) {
            if (t instanceof ClassTree ct) {
                String simple = ct.getSimpleName().toString();
                if (!simple.isEmpty()
                        && !simple.equals("module-info")
                        && !simple.equals("package-info")) {
                    siblings.add(simple);
                }
            }
        }

        SourceResolutionHints hints = new SourceResolutionHints(
                packageJvm, singleTypeImports, onDemandImports, siblings);

        Deque<String> enclosing = new ArrayDeque<>();
        for (Tree t : cu.getTypeDecls()) {
            if (t instanceof ClassTree ct) {
                indexType(uri, sourceUri, ct, packageJvm, enclosing, new HashSet<>(), hints, into);
            }
        }
    }

    private static void indexType(String uri,
                                  String sourceUri,
                                  ClassTree ct,
                                  String packageJvm,
                                  Deque<String> enclosing,
                                  Set<String> outerTypeParams,
                                  SourceResolutionHints hints,
                                  Index into) {
        String simple = ct.getSimpleName().toString();
        if (simple.isEmpty()) return;
        if (simple.equals("module-info") || simple.equals("package-info")) return;

        String localName;
        if (enclosing.isEmpty()) {
            localName = packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
        } else {
            localName = enclosing.peekLast() + "$" + simple;
        }
        localName = Interner.intern(localName);

        Set<String> classTypeParams = new HashSet<>(outerTypeParams);
        List<TypeParamRef> declaredTypeParams = new ArrayList<>();
        for (TypeParameterTree tp : ct.getTypeParameters()) {
            String tpName = tp.getName().toString();
            classTypeParams.add(tpName);
            declaredTypeParams.add(TypeParamRef.of(Interner.intern(tpName)));
        }

        TypeDeclKind declKind = declKind(ct);
        int modifiers = modifierFlags(ct.getModifiers());
        TypeRef superRef;
        if (ct.getExtendsClause() != null) {
            superRef = toTypeRef(ct.getExtendsClause(), classTypeParams, localName);
        } else if (declKind != TypeDeclKind.INTERFACE && declKind != TypeDeclKind.ANNOTATION) {
            superRef = TypeRef.resolved("java/lang/Object");
        } else {
            superRef = null;
        }
        List<TypeRef> interfaceRefs = new ArrayList<>();
        for (Tree intf : ct.getImplementsClause()) {
            interfaceRefs.add(toTypeRef(intf, classTypeParams, localName));
        }

        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();
        List<String> innerTypes = new ArrayList<>();
        List<ClassTree> nested = new ArrayList<>();

        for (Tree member : ct.getMembers()) {
            if (member instanceof VariableTree vt) {
                fields.add(toFieldEntry(uri, localName, vt, classTypeParams, localName));
            } else if (member instanceof MethodTree mt) {
                methods.add(toMethodEntry(uri, localName, mt, classTypeParams, localName));
            } else if (member instanceof ClassTree inner) {
                nested.add(inner);
                String innerName = Interner.intern(localName + "$" + inner.getSimpleName().toString());
                innerTypes.add(innerName);
            }
        }

        TypeEntry entry = new TypeEntry(
                uri,
                sourceUri,
                localName,
                modifiers,
                declKind,
                superRef,
                interfaceRefs,
                declaredTypeParams,
                fields,
                methods,
                innerTypes,
                annotationsOf(ct.getModifiers()),
                hints);
        into.add(entry);

        enclosing.addLast(localName);
        try {
            for (ClassTree inner : nested) {
                indexType(uri, sourceUri, inner, packageJvm, enclosing, classTypeParams, hints, into);
            }
        } finally {
            enclosing.removeLast();
        }
    }

    private static FieldEntry toFieldEntry(String uri, String owner, VariableTree vt,
                                           Set<String> typeParams, String ownerJvm) {
        return new FieldEntry(
                uri,
                owner,
                modifierFlags(vt.getModifiers()),
                Interner.intern(vt.getName().toString()),
                toTypeRef(vt.getType(), typeParams, ownerJvm),
                annotationsOf(vt.getModifiers()));
    }

    private static MethodEntry toMethodEntry(String uri, String owner, MethodTree mt,
                                             Set<String> classTypeParams, String ownerJvm) {
        Set<String> methodTypeParams = new HashSet<>(classTypeParams);
        for (TypeParameterTree tp : mt.getTypeParameters()) {
            methodTypeParams.add(tp.getName().toString());
        }

        List<TypeRef> paramRefs = new ArrayList<>();
        for (VariableTree p : mt.getParameters()) {
            paramRefs.add(toTypeRef(p.getType(), methodTypeParams, ownerJvm));
        }

        TypeRef returnRef = mt.getReturnType() == null
                ? TypeRef.Primitive.VOID
                : toTypeRef(mt.getReturnType(), methodTypeParams, ownerJvm);

        List<TypeRef> throwsRefs = new ArrayList<>();
        for (Tree th : mt.getThrows()) {
            throwsRefs.add(toTypeRef(th, methodTypeParams, ownerJvm));
        }

        List<TypeParamRef> declaredMethodTypeParams = new ArrayList<>();
        for (TypeParameterTree tp : mt.getTypeParameters()) {
            declaredMethodTypeParams.add(TypeParamRef.of(Interner.intern(tp.getName().toString())));
        }

        String name = Interner.intern(mt.getName().toString());
        return new MethodEntry(
                uri,
                owner,
                modifierFlags(mt.getModifiers()),
                name,
                returnRef,
                paramRefs,
                throwsRefs,
                declaredMethodTypeParams,
                isVarArgs(mt),
                mt.getBody() != null,
                mt.getDefaultValue() != null,
                annotationsOf(mt.getModifiers()));
    }

    private static TypeRef toTypeRef(Tree t, Set<String> typeParams, String ownerJvm) {
        if (t == null) return TypeRef.Primitive.VOID;
        if (t instanceof PrimitiveTypeTree pt) {
            return switch (pt.getPrimitiveTypeKind()) {
                case BOOLEAN -> TypeRef.Primitive.BOOLEAN;
                case BYTE -> TypeRef.Primitive.BYTE;
                case CHAR -> TypeRef.Primitive.CHAR;
                case DOUBLE -> TypeRef.Primitive.DOUBLE;
                case FLOAT -> TypeRef.Primitive.FLOAT;
                case INT -> TypeRef.Primitive.INT;
                case LONG -> TypeRef.Primitive.LONG;
                case SHORT -> TypeRef.Primitive.SHORT;
                case VOID -> TypeRef.Primitive.VOID;
                default -> new TypeRef.Resolved("java/lang/Object");
            };
        }
        if (t instanceof ArrayTypeTree at) {
            return new TypeRef.Array(toTypeRef(at.getType(), typeParams, ownerJvm));
        }
        if (t instanceof WildcardTree wt) {
            Tree bound = wt.getBound();
            return switch (wt.getKind()) {
                case UNBOUNDED_WILDCARD -> TypeRef.Wildcard.unbounded();
                case EXTENDS_WILDCARD -> TypeRef.Wildcard.extendsBound(toTypeRef(bound, typeParams, ownerJvm));
                case SUPER_WILDCARD -> TypeRef.Wildcard.superBound(toTypeRef(bound, typeParams, ownerJvm));
                default -> TypeRef.Wildcard.unbounded();
            };
        }
        if (t instanceof ParameterizedTypeTree pt) {
            TypeRef raw = toTypeRef(pt.getType(), typeParams, ownerJvm);
            List<TypeRef> args = new ArrayList<>();
            for (Tree arg : pt.getTypeArguments()) {
                args.add(toTypeRef(arg, typeParams, ownerJvm));
            }
            return new TypeRef.Parameterized(raw, args);
        }
        if (t instanceof IdentifierTree id) {
            String name = id.getName().toString();
            if (typeParams.contains(name)) {
                return TypeRef.typeVariable(name);
            }
            return TypeRef.unresolved(name);
        }
        if (t instanceof MemberSelectTree ms) {
            return typeRefForMemberSelect(ms, ownerJvm);
        }
        return TypeRef.resolved(t.toString().replace('.', '/'));
    }

    private static TypeRef typeRefForMemberSelect(MemberSelectTree ms, String ownerJvm) {
        String jvm = memberSelectJvmName(ms);
        if (ownerJvm != null) {
            int start = Math.max(ownerJvm.lastIndexOf('$'), ownerJvm.lastIndexOf('/')) + 1;
            String outerSimple = ownerJvm.substring(start);
            if (jvm.startsWith(outerSimple + "$")) {
                return TypeRef.resolved(Interner.intern(ownerJvm + jvm.substring(outerSimple.length())));
            }
        }
        return TypeRef.resolved(jvm);
    }

    /**
     * Builds a JVM binary name from a type {@link MemberSelectTree}, using
     * {@code /} between package segments and {@code $} for nested classes
     * (e.g. {@code java.util.Map.Entry} → {@code java/util/Map$Entry}).
     */
    private static String memberSelectJvmName(MemberSelectTree ms) {
        List<String> parts = new ArrayList<>();
        collectMemberSelectParts(ms, parts);
        int classStart = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (isClassLikeSimpleName(parts.get(i))) {
                classStart = i;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < classStart; i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        for (int i = classStart; i < parts.size(); i++) {
            if (i == classStart) {
                if (classStart > 0) sb.append('/');
                sb.append(parts.get(i));
            } else {
                sb.append('$').append(parts.get(i));
            }
        }
        return Interner.intern(sb.toString());
    }

    private static void collectMemberSelectParts(Tree tree, List<String> parts) {
        if (tree instanceof MemberSelectTree ms) {
            collectMemberSelectParts(ms.getExpression(), parts);
            parts.add(ms.getIdentifier().toString());
        } else if (tree instanceof IdentifierTree id) {
            parts.add(id.getName().toString());
        }
    }

    private static boolean isClassLikeSimpleName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static TypeDeclKind declKind(ClassTree ct) {
        return switch (ct.getKind()) {
            case INTERFACE -> TypeDeclKind.INTERFACE;
            case ENUM -> TypeDeclKind.ENUM;
            case ANNOTATION_TYPE -> TypeDeclKind.ANNOTATION;
            case RECORD -> TypeDeclKind.RECORD;
            default -> TypeDeclKind.CLASS;
        };
    }

    private static boolean isVarArgs(MethodTree mt) {
        if (mt.getParameters().isEmpty()) return false;
        VariableTree lastParam = mt.getParameters().get(mt.getParameters().size() - 1);
        if (lastParam instanceof JCTree.JCVariableDecl v) {
            return (v.mods.flags & Flags.VARARGS) != 0;
        }
        return lastParam.toString().contains("...");
    }

    private static int modifierFlags(ModifiersTree mods) {
        if (mods == null) return 0;
        int f = 0;
        for (javax.lang.model.element.Modifier m : mods.getFlags()) {
            switch (m) {
                case PUBLIC -> f |= Opcodes.ACC_PUBLIC;
                case PROTECTED -> f |= Opcodes.ACC_PROTECTED;
                case PRIVATE -> f |= Opcodes.ACC_PRIVATE;
                case STATIC -> f |= Opcodes.ACC_STATIC;
                case FINAL -> f |= Opcodes.ACC_FINAL;
                case ABSTRACT -> f |= Opcodes.ACC_ABSTRACT;
                case NATIVE -> f |= Opcodes.ACC_NATIVE;
                case SYNCHRONIZED -> f |= Opcodes.ACC_SYNCHRONIZED;
                case TRANSIENT -> f |= Opcodes.ACC_TRANSIENT;
                case VOLATILE -> f |= Opcodes.ACC_VOLATILE;
                case STRICTFP -> f |= Opcodes.ACC_STRICT;
                default -> { }
            }
        }
        return f;
    }

    private static List<AnnotationRef> annotationsOf(ModifiersTree mods) {
        if (mods == null || mods.getAnnotations().isEmpty()) return List.of();
        List<AnnotationRef> out = new ArrayList<>();
        for (AnnotationTree a : mods.getAnnotations()) {
            String name = Interner.intern(a.getAnnotationType().toString().replace('.', '/'));
            out.add(new AnnotationRef(name, Map.of()));
        }
        return out;
    }
}
