package ch.castleridge.javals.indexing.source.ecj;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AbstractVariableDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.AnnotationMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference;
import org.eclipse.jdt.internal.compiler.ast.CharLiteral;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.DoubleLiteral;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FalseLiteral;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FloatLiteral;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.Literal;
import org.eclipse.jdt.internal.compiler.ast.LongLiteral;
import org.eclipse.jdt.internal.compiler.ast.MarkerAnnotation;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NormalAnnotation;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleMemberAnnotation;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.ast.UnaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.AccessVisibility;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.ResourceUris;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * ECJ parse-only source indexer. Emits the same {@link SourceTypeEntry}
 * model as {@code JavacSourceIndexer}, without classpath resolution.
 */
final class EcjSourceIndexerEngine {

    /** Explicit source modifiers mirrored from javac's {@code Modifier} set. */
    private static final int EXPLICIT_MODIFIERS =
            Opcodes.ACC_PUBLIC
                    | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_PROTECTED
                    | Opcodes.ACC_STATIC
                    | Opcodes.ACC_FINAL
                    | Opcodes.ACC_SYNCHRONIZED
                    | Opcodes.ACC_VOLATILE
                    | Opcodes.ACC_TRANSIENT
                    | Opcodes.ACC_NATIVE
                    | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_STRICT;

    private EcjSourceIndexerEngine() {}

    static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        if (content == null) return;
        String resourceUriStr = ResourceUris.resolve(sourceUri, resourcePath);
        char[] source = toCharArray(content);
        String fileName = resourcePath == null || resourcePath.isEmpty()
                ? "Source.java"
                : resourcePath;
        ICompilationUnit compilationUnit = new CharArrayCompilationUnit(source, fileName.toCharArray());

        CompilerOptions options = new CompilerOptions(Map.of(
                CompilerOptions.OPTION_Source, CompilerOptions.VERSION_25,
                CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_25,
                CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_25));
        ProblemReporter problemReporter = new ProblemReporter(
                DefaultErrorHandlingPolicies.ignoreAllProblems(),
                options,
                new DefaultProblemFactory());
        Parser parser = new Parser(problemReporter, true);
        CompilationResult result = new CompilationResult(
                compilationUnit, 0, 0, options.maxProblemsPerUnit);
        CompilationUnitDeclaration unit = parser.parse(compilationUnit, result);
        if (unit == null) return;

        indexCompilationUnit(resourcePath, sourceUri, unit, into);

        if (resourceUriStr != null) {
            try {
                into.registerBloom(resourceUriStr, EcjIdentifierCollector.collectAndBuild(unit));
            } catch (RuntimeException | StackOverflowError e) {
                System.err.println("Failed to build identifier bloom filter for " + resourceUriStr
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void indexCompilationUnit(String uri,
                                             String sourceUri,
                                             CompilationUnitDeclaration unit,
                                             Index into) {
        if (unit.isModuleInfo() || unit.isPackageInfo()) return;

        String packageJvm = packageJvmName(unit.currentPackage);

        Map<String, String> singleTypeImports = new HashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        if (unit.imports != null) {
            for (ImportReference imp : unit.imports) {
                if (imp == null || imp.isStatic()) continue;
                if ((imp.bits & ASTNode.OnDemand) != 0) {
                    onDemandImports.add(tokensToJvm(imp.tokens, false));
                    continue;
                }
                char[][] tokens = imp.tokens;
                if (tokens == null || tokens.length == 0) continue;
                String simple = new String(tokens[tokens.length - 1]);
                singleTypeImports.put(simple, tokensToJvm(tokens, true));
            }
        }

        Set<String> siblings = new LinkedHashSet<>();
        if (unit.types != null) {
            for (TypeDeclaration td : unit.types) {
                if (td == null || td.name == null || td.name.length == 0) continue;
                String simple = new String(td.name);
                if (simple.equals("module-info") || simple.equals("package-info")) continue;
                siblings.add(simple);
            }
        }

        SourceResolutionHints hints = new SourceResolutionHints(
                packageJvm, singleTypeImports,
                EmptyArrays.toArray(onDemandImports, EmptyArrays.STRING), siblings);

        Deque<String> enclosing = new ArrayDeque<>();
        if (unit.types != null) {
            for (TypeDeclaration td : unit.types) {
                if (td == null) continue;
                indexType(uri, sourceUri, td, packageJvm, enclosing, new HashSet<>(), hints, into);
            }
        }
    }

    private static void indexType(String uri,
                                  String sourceUri,
                                  TypeDeclaration td,
                                  String packageJvm,
                                  Deque<String> enclosing,
                                  Set<String> outerTypeParams,
                                  SourceResolutionHints hints,
                                  Index into) {
        if (td.name == null || td.name.length == 0) return;
        String simple = new String(td.name);
        if (simple.equals("module-info") || simple.equals("package-info")) return;

        String localName;
        if (enclosing.isEmpty()) {
            localName = packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
        } else {
            localName = enclosing.peekLast() + "$" + simple;
        }

        Set<String> classTypeParams = new HashSet<>(outerTypeParams);
        if (td.typeParameters != null) {
            for (TypeParameter tp : td.typeParameters) {
                if (tp != null && tp.name != null) {
                    classTypeParams.add(new String(tp.name));
                }
            }
        }
        List<TypeParamRef> declaredTypeParams = new ArrayList<>();
        if (td.typeParameters != null) {
            for (TypeParameter tp : td.typeParameters) {
                if (tp != null) {
                    declaredTypeParams.add(toTypeParamRef(tp, classTypeParams, localName));
                }
            }
        }

        TypeDeclKind declKind = declKind(td);
        int modifiers = modifierFlags(td.modifiers);
        Type superRef = td.superclass != null
                ? toTypeRef(td.superclass, classTypeParams, localName)
                : null;
        List<Type> interfaceRefs = new ArrayList<>();
        if (td.superInterfaces != null) {
            for (TypeReference intf : td.superInterfaces) {
                if (intf != null) {
                    interfaceRefs.add(toTypeRef(intf, classTypeParams, localName));
                }
            }
        }

        List<TypeRef> permittedSubclasses = new ArrayList<>();
        if (td.permittedTypes != null) {
            for (TypeReference p : td.permittedTypes) {
                if (p != null) {
                    permittedSubclasses.add(toClassRef(p, classTypeParams, localName));
                }
            }
        }

        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();
        List<String> innerTypes = new ArrayList<>();
        List<TypeDeclaration> nested = new ArrayList<>();

        if (td.fields != null) {
            for (FieldDeclaration field : td.fields) {
                if (field == null || field instanceof Initializer) continue;
                int fieldFlags = modifierFlags(field.modifiers);
                if (isEnumConstant(field)) {
                    fieldFlags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;
                }
                String fieldName = field.name == null ? "" : new String(field.name);
                if (!AccessVisibility.shouldIndexMember(fieldFlags, fieldName)) {
                    continue;
                }
                fields.add(toFieldEntry(field, classTypeParams, localName));
            }
        }

        if (td.methods != null) {
            for (AbstractMethodDeclaration method : td.methods) {
                if (method == null || method.isClinit() || method.isDefaultConstructor()) continue;
                String methodName = methodName(method);
                if (!AccessVisibility.shouldIndexMember(modifierFlags(method.modifiers), methodName)) {
                    continue;
                }
                methods.add(toMethodEntry(method, classTypeParams, localName));
            }
        }

        if (td.memberTypes != null) {
            for (TypeDeclaration inner : td.memberTypes) {
                if (inner == null) continue;
                if (!AccessVisibility.shouldIndexType(modifierFlags(inner.modifiers))) {
                    continue;
                }
                nested.add(inner);
                String innerName = localName + "$" + new String(inner.name);
                innerTypes.add(innerName);
            }
        }

        TypeEntry entry = new SourceTypeEntry(
                uri,
                sourceUri,
                localName,
                modifiers,
                declKind,
                superRef,
                EmptyArrays.toArray(interfaceRefs, EmptyArrays.TYPE),
                EmptyArrays.toArray(declaredTypeParams, EmptyArrays.TYPE_PARAM),
                EmptyArrays.toArray(fields, EmptyArrays.FIELD),
                EmptyArrays.toArray(methods, EmptyArrays.METHOD),
                EmptyArrays.toArray(innerTypes, EmptyArrays.STRING),
                EmptyArrays.toArray(permittedSubclasses, EmptyArrays.TYPE_REF),
                EmptyArrays.RECORD_COMPONENT,
                annotationsOf(td.annotations, localName),
                hints);
        into.add(entry);

        enclosing.addLast(localName);
        try {
            for (TypeDeclaration inner : nested) {
                indexType(uri, sourceUri, inner, packageJvm, enclosing, classTypeParams, hints, into);
            }
        } finally {
            enclosing.removeLast();
        }
    }

    private static FieldEntry toFieldEntry(FieldDeclaration field,
                                           Set<String> typeParams,
                                           String ownerJvm) {
        int flags = modifierFlags(field.modifiers);
        if (isEnumConstant(field)) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;
        }
        Object constantValue = null;
        if ((flags & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
            constantValue = literalConstantValue(field.initialization);
        }
        Type type = isEnumConstant(field)
                ? TypeRef.resolved(ownerJvm)
                : toTypeRef(field.type, typeParams, ownerJvm);
        return new FieldEntry(
                flags,
                field.name == null ? "" : new String(field.name),
                type,
                constantValue,
                annotationsOf(field.annotations, ownerJvm));
    }

    private static MethodEntry toMethodEntry(AbstractMethodDeclaration method,
                                             Set<String> classTypeParams,
                                             String ownerJvm) {
        Set<String> methodTypeParams = new HashSet<>(classTypeParams);
        TypeParameter[] typeParameters = typeParametersOf(method);
        if (typeParameters != null) {
            for (TypeParameter tp : typeParameters) {
                if (tp != null && tp.name != null) {
                    methodTypeParams.add(new String(tp.name));
                }
            }
        }

        List<ParameterEntry> paramEntries = new ArrayList<>();
        if (method.arguments != null) {
            for (Argument p : method.arguments) {
                if (p == null) continue;
                paramEntries.add(new ParameterEntry(
                        p.name == null ? null : new String(p.name),
                        modifierFlags(p.modifiers),
                        toTypeRef(p.type, methodTypeParams, ownerJvm),
                        annotationsOf(p.annotations, ownerJvm)));
            }
        }

        Type returnRef;
        if (method.isConstructor()) {
            returnRef = Type.Primitive.VOID;
        } else if (method instanceof MethodDeclaration md && md.returnType != null) {
            returnRef = toTypeRef(md.returnType, methodTypeParams, ownerJvm);
        } else {
            returnRef = Type.Primitive.VOID;
        }

        List<Type> throwsRefs = new ArrayList<>();
        if (method.thrownExceptions != null) {
            for (TypeReference th : method.thrownExceptions) {
                if (th != null) {
                    throwsRefs.add(toTypeRef(th, methodTypeParams, ownerJvm));
                }
            }
        }

        List<TypeParamRef> declaredMethodTypeParams = new ArrayList<>();
        if (typeParameters != null) {
            for (TypeParameter tp : typeParameters) {
                if (tp != null) {
                    declaredMethodTypeParams.add(toTypeParamRef(tp, methodTypeParams, ownerJvm));
                }
            }
        }

        AnnotationValue defaultValue = null;
        if (method instanceof AnnotationMethodDeclaration amd && amd.defaultValue != null) {
            defaultValue = toAnnotationValue(amd.defaultValue, ownerJvm);
        }

        return new MethodEntry(
                modifierFlags(method.modifiers),
                methodName(method),
                returnRef,
                EmptyArrays.toArray(paramEntries, EmptyArrays.PARAMETER),
                EmptyArrays.toArray(throwsRefs, EmptyArrays.TYPE),
                EmptyArrays.toArray(declaredMethodTypeParams, EmptyArrays.TYPE_PARAM),
                isVarArgs(method),
                hasBody(method),
                defaultValue,
                annotationsOf(method.annotations, ownerJvm));
    }

    private static TypeParameter[] typeParametersOf(AbstractMethodDeclaration method) {
        if (method instanceof MethodDeclaration md) return md.typeParameters;
        if (method instanceof ConstructorDeclaration cd) return cd.typeParameters;
        return null;
    }

    private static String methodName(AbstractMethodDeclaration method) {
        if (method.isConstructor()) return "<init>";
        if (method.selector == null) return "";
        return new String(method.selector);
    }

    private static boolean hasBody(AbstractMethodDeclaration method) {
        if (method instanceof AnnotationMethodDeclaration) return false;
        if (method.isNative() || method.isAbstract()) return false;
        return (method.modifiers & ExtraCompilerModifiers.AccSemicolonBody) == 0;
    }

    private static boolean isVarArgs(AbstractMethodDeclaration method) {
        if (method.arguments == null || method.arguments.length == 0) return false;
        Argument last = method.arguments[method.arguments.length - 1];
        if (last == null) return false;
        return (last.bits & ASTNode.IsVarArgs) != 0
                || (method.modifiers & ClassFileConstants.AccVarargs) != 0;
    }

    private static TypeParamRef toTypeParamRef(TypeParameter tp,
                                               Set<String> visibleTypeParams,
                                               String ownerJvm) {
        String name = tp.name == null ? "" : new String(tp.name);
        List<Type> bounds = new ArrayList<>();
        if (tp.type != null) {
            bounds.add(toTypeRef(tp.type, visibleTypeParams, ownerJvm));
        }
        if (tp.bounds != null) {
            for (TypeReference b : tp.bounds) {
                if (b != null) {
                    bounds.add(toTypeRef(b, visibleTypeParams, ownerJvm));
                }
            }
        }
        if (bounds.isEmpty()) {
            return TypeParamRef.of(name);
        }
        return new TypeParamRef(name, EmptyArrays.toArray(bounds, EmptyArrays.TYPE));
    }

    private static Type toTypeRef(TypeReference t, Set<String> typeParams, String ownerJvm) {
        if (t == null) return Type.Primitive.VOID;

        Type result;
        if (t instanceof Wildcard w) {
            result = switch (w.kind) {
                case Wildcard.EXTENDS -> Type.Wildcard.extendsBound(
                        w.bound == null ? TypeRef.resolved("java/lang/Object")
                                : toTypeRef(w.bound, typeParams, ownerJvm));
                case Wildcard.SUPER -> Type.Wildcard.superBound(
                        w.bound == null ? TypeRef.resolved("java/lang/Object")
                                : toTypeRef(w.bound, typeParams, ownerJvm));
                default -> Type.Wildcard.unbounded();
            };
        } else if (t instanceof ParameterizedSingleTypeReference pst) {
            TypeRef raw = simpleClassRef(pst.token);
            List<Type> args = new ArrayList<>();
            if (pst.typeArguments != null) {
                for (TypeReference arg : pst.typeArguments) {
                    args.add(toTypeRef(arg, typeParams, ownerJvm));
                }
            }
            result = Type.parameterized(raw, EmptyArrays.toArray(args, EmptyArrays.TYPE));
            if (pst.dimensions > 0) {
                result = wrapArray(result, pst.dimensions);
            }
        } else if (t instanceof ParameterizedQualifiedTypeReference pqt) {
            TypeRef raw = typeRefForQualifiedTokens(pqt.tokens, ownerJvm);
            TypeReference[] lastArgs = lastTypeArguments(pqt.typeArguments);
            List<Type> args = new ArrayList<>();
            if (lastArgs != null) {
                for (TypeReference arg : lastArgs) {
                    if (arg != null) args.add(toTypeRef(arg, typeParams, ownerJvm));
                }
            }
            result = args.isEmpty()
                    ? raw
                    : Type.parameterized(raw, EmptyArrays.toArray(args, EmptyArrays.TYPE));
            int dims = pqt.dimensions();
            if (dims > 0) {
                result = wrapArray(result, dims);
            }
        } else if (t instanceof ArrayQualifiedTypeReference aqt) {
            result = wrapArray(typeRefForQualifiedTokens(aqt.tokens, ownerJvm), aqt.dimensions());
        } else if (t instanceof ArrayTypeReference atr
                && !(t instanceof ParameterizedSingleTypeReference)) {
            Type element = classRefFromSimple(atr.token, typeParams, ownerJvm);
            result = wrapArray(element, atr.dimensions);
        } else if (t instanceof QualifiedTypeReference qt) {
            result = typeRefForQualifiedTokens(qt.tokens, ownerJvm);
        } else if (t instanceof SingleTypeReference st) {
            result = classRefFromSimple(st.token, typeParams, ownerJvm);
        } else {
            char[][] typeName = t.getTypeName();
            result = typeName == null || typeName.length == 0
                    ? TypeRef.resolved("java/lang/Object")
                    : (typeName.length == 1
                            ? classRefFromSimple(typeName[0], typeParams, ownerJvm)
                            : typeRefForQualifiedTokens(typeName, ownerJvm));
        }

        return wrapTypeAnnotations(result, t.annotations, ownerJvm);
    }

    private static Type wrapTypeAnnotations(Type inner, Annotation[][] annotations, String ownerJvm) {
        if (annotations == null || annotations.length == 0) return inner;
        List<AnnotationRef> refs = new ArrayList<>();
        for (Annotation[] level : annotations) {
            if (level == null) continue;
            for (Annotation a : level) {
                if (a == null) continue;
                AnnotationRef ref = toAnnotationRef(a, ownerJvm);
                if (ref != null) refs.add(ref);
            }
        }
        return Type.Annotated.wrap(inner, EmptyArrays.toArray(refs, EmptyArrays.ANNOTATION_REF));
    }

    private static Type wrapArray(Type element, int dimensions) {
        Type cur = element;
        for (int i = 0; i < dimensions; i++) {
            cur = Type.array(cur);
        }
        return cur;
    }

    private static TypeReference[] lastTypeArguments(TypeReference[][] typeArguments) {
        if (typeArguments == null || typeArguments.length == 0) return null;
        return typeArguments[typeArguments.length - 1];
    }

    private static Type classRefFromSimple(char[] token, Set<String> typeParams, String ownerJvm) {
        if (token == null || token.length == 0) {
            return TypeRef.resolved("java/lang/Object");
        }
        String name = new String(token);
        Type primitive = primitiveType(name);
        if (primitive != null) return primitive;
        if (typeParams != null && typeParams.contains(name)) {
            return Type.typeVariable(name);
        }
        return TypeRef.unresolved(name);
    }

    private static TypeRef simpleClassRef(char[] token) {
        if (token == null || token.length == 0) {
            return TypeRef.resolved("java/lang/Object");
        }
        return TypeRef.unresolved(new String(token));
    }

    private static Type primitiveType(String name) {
        return switch (name) {
            case "boolean" -> Type.Primitive.BOOLEAN;
            case "byte" -> Type.Primitive.BYTE;
            case "char" -> Type.Primitive.CHAR;
            case "double" -> Type.Primitive.DOUBLE;
            case "float" -> Type.Primitive.FLOAT;
            case "int" -> Type.Primitive.INT;
            case "long" -> Type.Primitive.LONG;
            case "short" -> Type.Primitive.SHORT;
            case "void" -> Type.Primitive.VOID;
            default -> null;
        };
    }

    private static TypeRef toClassRef(TypeReference t, Set<String> typeParams, String ownerJvm) {
        Type type = toTypeRef(t, typeParams, ownerJvm);
        if (type instanceof Type.Annotated a) type = a.unwrap();
        if (type instanceof Type.Parameterized p) return p.raw();
        if (type instanceof TypeRef tr) return tr;
        return TypeRef.resolved("java/lang/Object");
    }

    /**
     * Build a {@link TypeRef} from qualified tokens, using the upper-case
     * segment heuristic for package vs nested-type boundaries, and rewriting
     * nested references rooted at the enclosing type against {@code ownerJvm}.
     */
    private static TypeRef typeRefForQualifiedTokens(char[][] tokens, String ownerJvm) {
        if (tokens == null || tokens.length == 0) {
            return TypeRef.resolved("java/lang/Object");
        }
        List<String> parts = new ArrayList<>(tokens.length);
        for (char[] token : tokens) {
            parts.add(new String(token));
        }
        String jvm = memberSelectJvmName(parts);
        if (ownerJvm != null) {
            int start = Math.max(ownerJvm.lastIndexOf('$'), ownerJvm.lastIndexOf('/')) + 1;
            String outerSimple = ownerJvm.substring(start);
            if (jvm.startsWith(outerSimple + "$")) {
                return TypeRef.resolved(ownerJvm + jvm.substring(outerSimple.length()));
            }
        }
        return TypeRef.resolved(jvm);
    }

    private static String memberSelectJvmName(List<String> parts) {
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
        return sb.toString();
    }

    private static boolean isClassLikeSimpleName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static TypeDeclKind declKind(TypeDeclaration td) {
        return switch (TypeDeclaration.kind(td.modifiers)) {
            case TypeDeclaration.INTERFACE_DECL -> TypeDeclKind.INTERFACE;
            case TypeDeclaration.ENUM_DECL -> TypeDeclKind.ENUM;
            case TypeDeclaration.ANNOTATION_TYPE_DECL -> TypeDeclKind.ANNOTATION;
            case TypeDeclaration.RECORD_DECL -> TypeDeclKind.RECORD;
            default -> TypeDeclKind.CLASS;
        };
    }

    private static boolean isEnumConstant(FieldDeclaration field) {
        return field.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT;
    }

    private static int modifierFlags(int modifiers) {
        return modifiers & EXPLICIT_MODIFIERS;
    }

    private static AnnotationRef[] annotationsOf(Annotation[] annotations, String ownerJvm) {
        if (annotations == null || annotations.length == 0) return EmptyArrays.ANNOTATION_REF;
        List<AnnotationRef> out = new ArrayList<>();
        for (Annotation a : annotations) {
            if (a == null) continue;
            AnnotationRef ref = toAnnotationRef(a, ownerJvm);
            if (ref != null) out.add(ref);
        }
        return EmptyArrays.toArray(out, EmptyArrays.ANNOTATION_REF);
    }

    private static AnnotationRef toAnnotationRef(Annotation a, String ownerJvm) {
        if (a == null || a.type == null) return null;
        TypeRef annotationType = toClassRef(a.type, Set.of(), ownerJvm);
        if (a instanceof MarkerAnnotation) {
            return new AnnotationRef(annotationType, Map.of());
        }
        if (a instanceof SingleMemberAnnotation sma) {
            Map<String, AnnotationValue> values = new HashMap<>();
            values.put("value", toAnnotationValue(sma.memberValue, ownerJvm));
            return new AnnotationRef(annotationType, values);
        }
        if (a instanceof NormalAnnotation na) {
            MemberValuePair[] pairs = na.memberValuePairs;
            if (pairs == null || pairs.length == 0) {
                return new AnnotationRef(annotationType, Map.of());
            }
            Map<String, AnnotationValue> values = new HashMap<>();
            for (MemberValuePair pair : pairs) {
                if (pair == null || pair.name == null) continue;
                values.put(new String(pair.name), toAnnotationValue(pair.value, ownerJvm));
            }
            return new AnnotationRef(annotationType, values);
        }
        MemberValuePair[] pairs = a.memberValuePairs();
        if (pairs == null || pairs.length == 0) {
            return new AnnotationRef(annotationType, Map.of());
        }
        Map<String, AnnotationValue> values = new HashMap<>();
        for (MemberValuePair pair : pairs) {
            if (pair == null || pair.name == null) continue;
            values.put(new String(pair.name), toAnnotationValue(pair.value, ownerJvm));
        }
        return new AnnotationRef(annotationType, values);
    }

    private static AnnotationValue toAnnotationValue(Expression expr, String ownerJvm) {
        if (expr == null) {
            return new AnnotationValue.Unsupported("missing expression");
        }
        if (expr instanceof NullLiteral) {
            return new AnnotationValue.Unsupported("null literal");
        }
        if (expr instanceof StringLiteral sl) {
            ensureConstant(sl);
            if (sl.constant != null && sl.constant != Constant.NotAConstant) {
                return new AnnotationValue.Str(sl.constant.stringValue());
            }
            return new AnnotationValue.Str(new String(sl.source()));
        }
        if (expr instanceof TrueLiteral || expr instanceof FalseLiteral
                || expr instanceof IntLiteral || expr instanceof LongLiteral
                || expr instanceof FloatLiteral || expr instanceof DoubleLiteral
                || expr instanceof CharLiteral) {
            Object boxed = literalConstantValue(expr);
            if (boxed == null) {
                return new AnnotationValue.Unsupported("unresolved literal");
            }
            // AnnotationValue.Primitive rejects Integer-encoded booleans/chars
            // only when they are Boolean/Character wrappers — mirror javac:
            // for annotation values keep Boolean/Character wrappers when possible.
            if (expr instanceof TrueLiteral) return new AnnotationValue.Primitive(Boolean.TRUE);
            if (expr instanceof FalseLiteral) return new AnnotationValue.Primitive(Boolean.FALSE);
            if (expr instanceof CharLiteral) {
                ensureConstant((Literal) expr);
                if (expr.constant != null && expr.constant != Constant.NotAConstant) {
                    return new AnnotationValue.Primitive(expr.constant.charValue());
                }
            }
            if (boxed instanceof Boolean || boxed instanceof Character
                    || boxed instanceof Byte || boxed instanceof Short
                    || boxed instanceof Integer || boxed instanceof Long
                    || boxed instanceof Float || boxed instanceof Double) {
                return new AnnotationValue.Primitive(boxed);
            }
            return new AnnotationValue.Unsupported("unsupported literal");
        }
        if (expr instanceof UnaryExpression unary) {
            int operator = (unary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            Expression operand = unary.expression;
            if (operand instanceof Literal lit && (operator == OperatorIds.MINUS || operator == OperatorIds.PLUS)) {
                Object value = literalConstantValue(lit);
                if (value instanceof Number n) {
                    Object out = operator == OperatorIds.MINUS ? negateNumber(n) : n;
                    if (out != null) {
                        return new AnnotationValue.Primitive(out);
                    }
                }
            }
            return new AnnotationValue.Unsupported("unary expression");
        }
        if (expr instanceof ArrayInitializer arr) {
            Expression[] inits = arr.expressions;
            if (inits == null) {
                return new AnnotationValue.Arr(EmptyArrays.ANNOTATION_VALUE);
            }
            AnnotationValue[] elements = new AnnotationValue[inits.length];
            for (int i = 0; i < inits.length; i++) {
                elements[i] = toAnnotationValue(inits[i], ownerJvm);
            }
            return new AnnotationValue.Arr(elements);
        }
        if (expr instanceof Annotation nested) {
            AnnotationRef ref = toAnnotationRef(nested, ownerJvm);
            if (ref == null) {
                return new AnnotationValue.Unsupported("nested annotation");
            }
            return new AnnotationValue.Nested(ref);
        }
        if (expr instanceof ClassLiteralAccess cla) {
            return new AnnotationValue.ClassRef(typeRefForExpression(cla.type, ownerJvm));
        }
        if (expr instanceof QualifiedNameReference qnr) {
            char[][] tokens = qnr.tokens;
            if (tokens == null || tokens.length == 0) {
                return new AnnotationValue.Unsupported("empty qualified name");
            }
            String selected = new String(tokens[tokens.length - 1]);
            if (tokens.length == 1) {
                return new AnnotationValue.EnumConst(TypeRef.unresolved("?"), selected);
            }
            char[][] typeTokens = new char[tokens.length - 1][];
            System.arraycopy(tokens, 0, typeTokens, 0, tokens.length - 1);
            return new AnnotationValue.EnumConst(typeRefForQualifiedTokens(typeTokens, ownerJvm), selected);
        }
        if (expr instanceof SingleNameReference snr) {
            return new AnnotationValue.EnumConst(
                    TypeRef.unresolved("?"),
                    snr.token == null ? "?" : new String(snr.token));
        }
        return new AnnotationValue.Unsupported("non-constant expression");
    }

    private static Type typeRefForExpression(TypeReference type, String ownerJvm) {
        if (type == null) return TypeRef.unresolved("?");
        Type t = toTypeRef(type, Set.of(), ownerJvm);
        if (t instanceof Type.Annotated a) t = a.unwrap();
        return t;
    }

    /**
     * Best-effort literal-initializer extraction for {@code static final}
     * fields. Mirrors javac indexer conventions (boolean/char as Integer).
     */
    private static Object literalConstantValue(Expression initializer) {
        if (initializer == null) return null;
        if (initializer instanceof NullLiteral) return null;
        if (initializer instanceof Literal lit) {
            ensureConstant(lit);
            return constantToBoxed(lit);
        }
        if (initializer instanceof UnaryExpression unary) {
            int operator = (unary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            if (unary.expression instanceof Literal lit
                    && (operator == OperatorIds.MINUS || operator == OperatorIds.PLUS)) {
                Object value = literalConstantValue(lit);
                if (value instanceof Number n) {
                    return operator == OperatorIds.MINUS ? negateNumber(n) : n;
                }
            }
        }
        return null;
    }

    private static void ensureConstant(Literal lit) {
        if (lit.constant == null || lit.constant == Constant.NotAConstant) {
            lit.computeConstant();
        }
    }

    private static Object constantToBoxed(Literal lit) {
        if (lit instanceof TrueLiteral) return 1;
        if (lit instanceof FalseLiteral) return 0;
        if (lit.constant == null || lit.constant == Constant.NotAConstant) {
            if (lit instanceof IntLiteral il) return il.value;
            return null;
        }
        Constant c = lit.constant;
        return switch (c.typeID()) {
            case TypeIds.T_boolean -> c.booleanValue() ? 1 : 0;
            case TypeIds.T_char -> (int) c.charValue();
            case TypeIds.T_byte -> (int) c.byteValue();
            case TypeIds.T_short -> (int) c.shortValue();
            case TypeIds.T_int -> c.intValue();
            case TypeIds.T_long -> c.longValue();
            case TypeIds.T_float -> c.floatValue();
            case TypeIds.T_double -> c.doubleValue();
            case TypeIds.T_JavaLangString -> c.stringValue();
            default -> null;
        };
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

    private static String packageJvmName(ImportReference currentPackage) {
        if (currentPackage == null || currentPackage.tokens == null || currentPackage.tokens.length == 0) {
            return "";
        }
        return tokensToJvm(currentPackage.tokens, false);
    }

    private static String tokensToJvm(char[][] tokens, boolean nestedClassHeuristic) {
        if (tokens == null || tokens.length == 0) return "";
        if (!nestedClassHeuristic) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(tokens[i]);
            }
            return sb.toString();
        }
        List<String> parts = new ArrayList<>(tokens.length);
        for (char[] token : tokens) {
            parts.add(new String(token));
        }
        return memberSelectJvmName(parts);
    }

    private static char[] toCharArray(CharSequence content) {
        if (content instanceof String s) return s.toCharArray();
        int n = content.length();
        char[] out = new char[n];
        for (int i = 0; i < n; i++) {
            out[i] = content.charAt(i);
        }
        return out;
    }

    /** In-memory {@link ICompilationUnit} backed by a char array. */
    private static final class CharArrayCompilationUnit implements ICompilationUnit {
        private final char[] contents;
        private final char[] fileName;
        private final char[] mainTypeName;

        CharArrayCompilationUnit(char[] contents, char[] fileName) {
            this.contents = contents;
            this.fileName = fileName;
            this.mainTypeName = mainTypeNameOf(fileName);
        }

        @Override
        public char[] getContents() {
            return contents;
        }

        @Override
        public char[] getFileName() {
            return fileName;
        }

        @Override
        public char[] getMainTypeName() {
            return mainTypeName;
        }

        @Override
        public char[][] getPackageName() {
            return null;
        }

        @Override
        public boolean ignoreOptionalProblems() {
            return true;
        }

        private static char[] mainTypeNameOf(char[] fileName) {
            String name = new String(fileName);
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            String base = slash >= 0 ? name.substring(slash + 1) : name;
            if (base.endsWith(".java")) {
                base = base.substring(0, base.length() - 5);
            }
            return base.toCharArray();
        }
    }
}
