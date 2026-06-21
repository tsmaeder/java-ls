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
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;

import ch.castleridge.javals.indexing.index.InMemorySource;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.intern.Interner;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.Type;
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
                if (resourceUriStr != null) {
                    into.registerBloom(resourceUriStr, IdentifierCollector.collectAndBuild(cu));
                }
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
            Tree qualId = imp.getQualifiedIdentifier();
            String qn = qualId.toString();
            if (qn.endsWith(".*")) {
                String pkg = qn.substring(0, qn.length() - 2).replace('.', '/');
                onDemandImports.add(pkg);
                continue;
            }
            int dot = qn.lastIndexOf('.');
            String simple = dot < 0 ? qn : qn.substring(dot + 1);
            String jvmName = qualId instanceof MemberSelectTree ms
                    ? memberSelectJvmName(ms)
                    : qn.replace('.', '/');
            singleTypeImports.put(simple, jvmName);
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
        // Two-phase: enter the names first so bounds that reference
        // sibling type parameters (F-bounded generics like
        // <T extends Comparable<T>>) resolve.
        for (TypeParameterTree tp : ct.getTypeParameters()) {
            classTypeParams.add(tp.getName().toString());
        }
        List<TypeParamRef> declaredTypeParams = new ArrayList<>();
        for (TypeParameterTree tp : ct.getTypeParameters()) {
            declaredTypeParams.add(toTypeParamRef(tp, classTypeParams, localName));
        }

        TypeDeclKind declKind = declKind(ct);
        int modifiers = modifierFlags(ct.getModifiers());
        // Only an explicit `extends` clause is recorded here. When the header
        // carries none, the implicit supertype depends on the declaration
        // kind (java/lang/Object for a class, java/lang/Enum for an enum,
        // java/lang/Record for a record, none for an interface/annotation).
        // We deliberately leave it null and let the index class reader supply
        // the right default, where the declKind is known and the supertype can
        // be resolved against the full index.
        Type superRef = ct.getExtendsClause() != null
                ? toTypeRef(ct.getExtendsClause(), classTypeParams, localName)
                : null;
        List<Type> interfaceRefs = new ArrayList<>();
        for (Tree intf : ct.getImplementsClause()) {
            interfaceRefs.add(toTypeRef(intf, classTypeParams, localName));
        }

        List<TypeRef> permittedSubclasses = new ArrayList<>();
        List<? extends Tree> permits = ct.getPermitsClause();
        if (permits != null) {
            for (Tree p : permits) {
                permittedSubclasses.add(toClassRef(p, classTypeParams, localName));
            }
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
                permittedSubclasses,
                List.of(),
                annotationsOf(ct.getModifiers(), localName),
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
        int flags = modifierFlags(vt.getModifiers());
        if (isEnumConstant(vt)) {
            // An enum constant is implicitly public, static and final and
            // carries ACC_ENUM, but its source ModifiersTree lists none of
            // these. Without them the synthesized field is package-private and
            // non-static, so `MyEnum.CONSTANT` references report "cannot find
            // symbol: variable CONSTANT".
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;
        }
        Object constantValue = null;
        if ((flags & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
            constantValue = literalConstantValue(vt.getInitializer());
        }
        return new FieldEntry(
                uri,
                owner,
                flags,
                Interner.intern(vt.getName().toString()),
                toTypeRef(vt.getType(), typeParams, ownerJvm),
                constantValue,
                annotationsOf(vt.getModifiers(), ownerJvm));
    }

    /**
     * Best-effort literal-initializer extraction for {@code static final}
     * fields. Only direct literal expressions (and unary +/- over a
     * numeric literal) qualify - anything more elaborate would require a
     * symbol table the source indexer doesn't have. Returning {@code null}
     * simply means "no compile-time constant known" downstream.
     */
    private static Object literalConstantValue(ExpressionTree initializer) {
        if (initializer instanceof LiteralTree lit) {
            Object v = lit.getValue();
            if (v == null) return null;
            // javac stores boolean/char constants as Integer (0/1 and the
            // unboxed code unit) in VarSymbol; mirror that convention here
            // so IndexClassReader can hand it to setData() unchanged.
            if (v instanceof Boolean b) return b ? 1 : 0;
            if (v instanceof Character c) return (int) c.charValue();
            if (v instanceof Byte b) return b.intValue();
            if (v instanceof Short s) return s.intValue();
            return v;
        }
        if (initializer instanceof UnaryTree unary
                && unary.getExpression() instanceof LiteralTree lit
                && lit.getValue() instanceof Number n) {
            return switch (unary.getKind()) {
                case UNARY_MINUS -> negateNumber(n);
                case UNARY_PLUS -> n;
                default -> null;
            };
        }
        return null;
    }

    private static MethodEntry toMethodEntry(String uri, String owner, MethodTree mt,
                                             Set<String> classTypeParams, String ownerJvm) {
        Set<String> methodTypeParams = new HashSet<>(classTypeParams);
        for (TypeParameterTree tp : mt.getTypeParameters()) {
            methodTypeParams.add(tp.getName().toString());
        }

        List<ParameterEntry> paramEntries = new ArrayList<>();
        for (VariableTree p : mt.getParameters()) {
            paramEntries.add(new ParameterEntry(
                    Interner.intern(p.getName().toString()),
                    modifierFlags(p.getModifiers()),
                    toTypeRef(p.getType(), methodTypeParams, ownerJvm),
                    annotationsOf(p.getModifiers(), ownerJvm)));
        }

        Type returnRef = mt.getReturnType() == null
                ? Type.Primitive.VOID
                : toTypeRef(mt.getReturnType(), methodTypeParams, ownerJvm);

        List<Type> throwsRefs = new ArrayList<>();
        for (Tree th : mt.getThrows()) {
            throwsRefs.add(toTypeRef(th, methodTypeParams, ownerJvm));
        }

        List<TypeParamRef> declaredMethodTypeParams = new ArrayList<>();
        for (TypeParameterTree tp : mt.getTypeParameters()) {
            declaredMethodTypeParams.add(toTypeParamRef(tp, methodTypeParams, ownerJvm));
        }

        String name = Interner.intern(mt.getName().toString());
        Tree defaultTree = mt.getDefaultValue();
        AnnotationValue defaultValue = defaultTree instanceof ExpressionTree dt
                ? toAnnotationValue(dt, ownerJvm)
                : null;
        return new MethodEntry(
                uri,
                owner,
                modifierFlags(mt.getModifiers()),
                name,
                returnRef,
                paramEntries,
                throwsRefs,
                declaredMethodTypeParams,
                isVarArgs(mt),
                mt.getBody() != null,
                defaultValue,
                annotationsOf(mt.getModifiers(), ownerJvm));
    }

    private static TypeParamRef toTypeParamRef(TypeParameterTree tp,
                                               Set<String> visibleTypeParams,
                                               String ownerJvm) {
        String name = Interner.intern(tp.getName().toString());
        List<? extends Tree> boundTrees = tp.getBounds();
        if (boundTrees == null || boundTrees.isEmpty()) {
            return TypeParamRef.of(name);
        }
        List<Type> bounds = new ArrayList<>(boundTrees.size());
        for (Tree b : boundTrees) {
            bounds.add(toTypeRef(b, visibleTypeParams, ownerJvm));
        }
        return new TypeParamRef(name, bounds);
    }

    private static Type toTypeRef(Tree t, Set<String> typeParams, String ownerJvm) {
        if (t == null) return Type.Primitive.VOID;
        if (t instanceof com.sun.source.tree.AnnotatedTypeTree annotated) {
            Type inner = toTypeRef(annotated.getUnderlyingType(), typeParams, ownerJvm);
            List<AnnotationRef> typeUseAnnotations = new ArrayList<>();
            for (AnnotationTree a : annotated.getAnnotations()) {
                AnnotationRef ref = toAnnotationRef(a, ownerJvm);
                if (ref != null) typeUseAnnotations.add(ref);
            }
            return Type.Annotated.wrap(inner, typeUseAnnotations);
        }
        if (t instanceof PrimitiveTypeTree pt) {
            return switch (pt.getPrimitiveTypeKind()) {
                case BOOLEAN -> Type.Primitive.BOOLEAN;
                case BYTE -> Type.Primitive.BYTE;
                case CHAR -> Type.Primitive.CHAR;
                case DOUBLE -> Type.Primitive.DOUBLE;
                case FLOAT -> Type.Primitive.FLOAT;
                case INT -> Type.Primitive.INT;
                case LONG -> Type.Primitive.LONG;
                case SHORT -> Type.Primitive.SHORT;
                case VOID -> Type.Primitive.VOID;
                default -> TypeRef.resolved("java/lang/Object");
            };
        }
        if (t instanceof ArrayTypeTree at) {
            return new Type.Array(toTypeRef(at.getType(), typeParams, ownerJvm));
        }
        if (t instanceof WildcardTree wt) {
            Tree bound = wt.getBound();
            return switch (wt.getKind()) {
                case UNBOUNDED_WILDCARD -> Type.Wildcard.unbounded();
                case EXTENDS_WILDCARD -> Type.Wildcard.extendsBound(toTypeRef(bound, typeParams, ownerJvm));
                case SUPER_WILDCARD -> Type.Wildcard.superBound(toTypeRef(bound, typeParams, ownerJvm));
                default -> Type.Wildcard.unbounded();
            };
        }
        if (t instanceof ParameterizedTypeTree pt) {
            TypeRef raw = toClassRef(pt.getType(), typeParams, ownerJvm);
            List<Type> args = new ArrayList<>();
            for (Tree arg : pt.getTypeArguments()) {
                args.add(toTypeRef(arg, typeParams, ownerJvm));
            }
            return new Type.Parameterized(raw, args);
        }
        if (t instanceof IdentifierTree id) {
            String name = id.getName().toString();
            if (typeParams.contains(name)) {
                return Type.typeVariable(name);
            }
            return TypeRef.unresolved(name);
        }
        if (t instanceof MemberSelectTree ms) {
            return typeRefForMemberSelect(ms, ownerJvm);
        }
        return TypeRef.resolved(t.toString().replace('.', '/'));
    }

    /**
     * Narrow a type tree to a {@link TypeRef} for positions that only
     * accept class types (e.g. {@code permits} clauses and parameterized
     * raw types).
     */
    private static TypeRef toClassRef(Tree t, Set<String> typeParams, String ownerJvm) {
        Type type = toTypeRef(t, typeParams, ownerJvm);
        if (type instanceof TypeRef tr) return tr;
        return TypeRef.resolved("java/lang/Object");
    }

    /**
     * Build a {@link TypeRef} from a member-select type tree.
     *
     * <p>When the selection is rooted at a nested type of the enclosing
     * declaration (e.g. {@code Outer.Inner} inside {@code Outer}), it is
     * rewritten against {@code ownerJvm} so the binary {@code $} form is
     * produced directly.
     *
     * <p>Otherwise the member select collapses to a package-less
     * {@code Outer$Nested} (via the upper-case-segment heuristic in
     * {@link #memberSelectJvmName}). We deliberately do <em>not</em>
     * qualify the package here: a member select rooted at an imported
     * simple name (e.g. {@code Base64.Encoder} under {@code import
     * java.util.*}) is left unqualified and finished lazily by
     * {@code TypeRefResolver}, which has the index and classpath needed to
     * pick the right package. This keeps import resolution in one place.
     */
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

    private static boolean isEnumConstant(VariableTree vt) {
        // The parser sets Flags.ENUM on enum-constant declarations; this is the
        // only reliable structural signal (the public ModifiersTree API exposes
        // no ENUM modifier).
        return vt instanceof JCTree.JCVariableDecl v && (v.mods.flags & Flags.ENUM) != 0;
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

    private static List<AnnotationRef> annotationsOf(ModifiersTree mods, String ownerJvm) {
        if (mods == null || mods.getAnnotations().isEmpty()) return List.of();
        List<AnnotationRef> out = new ArrayList<>();
        for (AnnotationTree a : mods.getAnnotations()) {
            out.add(toAnnotationRef(a, ownerJvm));
        }
        return out;
    }

    /**
     * Build an {@link AnnotationRef} from an {@link AnnotationTree},
     * walking each supplied element expression into an
     * {@link AnnotationValue}. Arguments without an explicit name fall
     * back to the {@code value} element per Java's single-element
     * annotation shorthand.
     */
    private static AnnotationRef toAnnotationRef(AnnotationTree a, String ownerJvm) {
        TypeRef annotationType = toClassRef(a.getAnnotationType(), Set.of(), ownerJvm);
        List<? extends ExpressionTree> args = a.getArguments();
        if (args == null || args.isEmpty()) {
            return new AnnotationRef(annotationType, Map.of());
        }
        Map<String, AnnotationValue> values = new HashMap<>();
        for (ExpressionTree arg : args) {
            String elementName;
            ExpressionTree valueExpr;
            if (arg instanceof AssignmentTree assign && assign.getVariable() instanceof IdentifierTree id) {
                elementName = id.getName().toString();
                valueExpr = assign.getExpression();
            } else {
                elementName = "value";
                valueExpr = arg;
            }
            AnnotationValue value = toAnnotationValue(valueExpr, ownerJvm);
            values.put(Interner.intern(elementName), value);
        }
        return new AnnotationRef(annotationType, values);
    }

    /**
     * Best-effort conversion of an annotation-element expression into an
     * {@link AnnotationValue}. The source indexer has no symbol table,
     * so anything that isn't a literal, array literal, class literal,
     * enum-shaped name reference or nested annotation collapses to
     * {@link AnnotationValue.Unsupported} - the symbol-side converter
     * then drops the element from the attribute map, which is the right
     * semantic for "value not known".
     */
    private static AnnotationValue toAnnotationValue(ExpressionTree expr, String ownerJvm) {
        if (expr == null) {
            return new AnnotationValue.Unsupported("missing expression");
        }
        if (expr instanceof LiteralTree lit) {
            Object value = lit.getValue();
            if (value == null) {
                return new AnnotationValue.Unsupported("null literal");
            }
            if (value instanceof String s) {
                return new AnnotationValue.Str(s);
            }
            return new AnnotationValue.Primitive(value);
        }
        if (expr instanceof UnaryTree unary) {
            // Allow negative numeric literals: -1, -1.5, ...
            ExpressionTree operand = unary.getExpression();
            if (operand instanceof LiteralTree litOperand && litOperand.getValue() instanceof Number n) {
                switch (unary.getKind()) {
                    case UNARY_MINUS -> {
                        Object negated = negateNumber(n);
                        if (negated != null) {
                            return new AnnotationValue.Primitive(negated);
                        }
                    }
                    case UNARY_PLUS -> {
                        return new AnnotationValue.Primitive(n);
                    }
                    default -> { /* fall through to Unsupported */ }
                }
            }
            return new AnnotationValue.Unsupported("unary expression");
        }
        if (expr instanceof NewArrayTree arr) {
            List<? extends ExpressionTree> inits = arr.getInitializers();
            if (inits == null) {
                return new AnnotationValue.Arr(List.of());
            }
            List<AnnotationValue> elements = new ArrayList<>(inits.size());
            for (ExpressionTree e : inits) {
                elements.add(toAnnotationValue(e, ownerJvm));
            }
            return new AnnotationValue.Arr(elements);
        }
        if (expr instanceof AnnotationTree nested) {
            return new AnnotationValue.Nested(toAnnotationRef(nested, ownerJvm));
        }
        // Foo.class -> ClassRef; Foo.BAR / Foo.Bar.BAZ -> tentative EnumConst; bare Identifier -> tentative EnumConst.
        if (expr instanceof MemberSelectTree ms) {
            String selected = ms.getIdentifier().toString();
            if (selected.equals("class")) {
                return new AnnotationValue.ClassRef(typeRefForExpression(ms.getExpression()));
            }
            return new AnnotationValue.EnumConst(typeRefForExpression(ms.getExpression()), Interner.intern(selected));
        }
        if (expr instanceof IdentifierTree id) {
            // Unqualified identifier: could be an enum constant imported
            // statically or via a static import. Without resolution we
            // can only encode the simple name; the symbol-side converter
            // will downgrade to Unsupported if it can't bind.
            return new AnnotationValue.EnumConst(
                    TypeRef.unresolved("?"),
                    Interner.intern(id.getName().toString()));
        }
        return new AnnotationValue.Unsupported("non-constant expression");
    }

    /**
     * Build a {@link Type} suitable for a class-literal qualifier or
     * an enum-constant qualifier. Only {@link IdentifierTree} and
     * {@link MemberSelectTree} chains are supported - everything else
     * yields {@link TypeRef.Unresolved} sentinel "?" so the symbol-side
     * converter can fall back gracefully.
     */
    private static Type typeRefForExpression(ExpressionTree e) {
        if (e instanceof IdentifierTree id) {
            return TypeRef.unresolved(id.getName().toString());
        }
        if (e instanceof MemberSelectTree ms) {
            String jvm = qualifiedToJvm(ms);
            return jvm == null ? TypeRef.unresolved("?") : TypeRef.resolved(jvm);
        }
        if (e instanceof ArrayTypeTree at) {
            return new Type.Array(typeRefForExpression((ExpressionTree) at.getType()));
        }
        if (e instanceof PrimitiveTypeTree pt) {
            return switch (pt.getPrimitiveTypeKind()) {
                case BOOLEAN -> Type.Primitive.BOOLEAN;
                case BYTE -> Type.Primitive.BYTE;
                case CHAR -> Type.Primitive.CHAR;
                case DOUBLE -> Type.Primitive.DOUBLE;
                case FLOAT -> Type.Primitive.FLOAT;
                case INT -> Type.Primitive.INT;
                case LONG -> Type.Primitive.LONG;
                case SHORT -> Type.Primitive.SHORT;
                case VOID -> Type.Primitive.VOID;
                default -> TypeRef.resolved("java/lang/Object");
            };
        }
        return TypeRef.unresolved("?");
    }

    /**
     * Best-effort conversion of a dotted member-select chain into a JVM
     * binary name (packages joined with '/', nested types with '$'). The
     * indexer cannot know without symbol resolution where the
     * package/type boundary sits, so we use the simple-name-starts-with-
     * upper-case heuristic that's also used by
     * {@link #memberSelectJvmName}.
     */
    private static String qualifiedToJvm(MemberSelectTree ms) {
        List<String> parts = new ArrayList<>();
        collectMemberSelectParts(ms, parts);
        if (parts.isEmpty()) return null;
        int classStart = parts.size();
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

    private static Object negateNumber(Number n) {
        if (n instanceof Integer i) return -i;
        if (n instanceof Long l) return -l;
        if (n instanceof Float f) return -f;
        if (n instanceof Double d) return -d;
        if (n instanceof Short s) return (short) -s;
        if (n instanceof Byte b) return (byte) -b;
        return null;
    }
}
