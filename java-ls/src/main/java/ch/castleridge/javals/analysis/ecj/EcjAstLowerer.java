/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ArrayReference;
import org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference;
import org.eclipse.jdt.internal.compiler.ast.AssertStatement;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.BreakStatement;
import org.eclipse.jdt.internal.compiler.ast.CaseStatement;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.Clinit;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompoundAssignment;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ContinueStatement;
import org.eclipse.jdt.internal.compiler.ast.DoStatement;
import org.eclipse.jdt.internal.compiler.ast.EmptyStatement;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ForStatement;
import org.eclipse.jdt.internal.compiler.ast.ForeachStatement;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.InstanceOfExpression;
import org.eclipse.jdt.internal.compiler.ast.LabeledStatement;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.Literal;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MarkerAnnotation;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NormalAnnotation;
import org.eclipse.jdt.internal.compiler.ast.OperatorIds;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.PostfixExpression;
import org.eclipse.jdt.internal.compiler.ast.PrefixExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedThisReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleMemberAnnotation;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.SuperReference;
import org.eclipse.jdt.internal.compiler.ast.SwitchExpression;
import org.eclipse.jdt.internal.compiler.ast.SwitchStatement;
import org.eclipse.jdt.internal.compiler.ast.SynchronizedStatement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.ThrowStatement;
import org.eclipse.jdt.internal.compiler.ast.TryStatement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.ast.UnaryExpression;
import org.eclipse.jdt.internal.compiler.ast.WhileStatement;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.ast.YieldStatement;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;

import ch.castleridge.javals.analysis.AttachedSource;
import ch.castleridge.javals.analysis.FileUris;
import ch.castleridge.javals.ast.AnnoArg;
import ch.castleridge.javals.ast.ArrayAccessExpr;
import ch.castleridge.javals.ast.ArrayInitExpr;
import ch.castleridge.javals.ast.ArrayTypeNode;
import ch.castleridge.javals.ast.AssertStmt;
import ch.castleridge.javals.ast.AssignExpr;
import ch.castleridge.javals.ast.BinaryExpr;
import ch.castleridge.javals.ast.BreakStmt;
import ch.castleridge.javals.ast.CallExpr;
import ch.castleridge.javals.ast.CaseLabel;
import ch.castleridge.javals.ast.CastExpr;
import ch.castleridge.javals.ast.CatchClause;
import ch.castleridge.javals.ast.ClassLiteralExpr;
import ch.castleridge.javals.ast.CompactConstructorDecl;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConditionalExpr;
import ch.castleridge.javals.ast.ConstantLabel;
import ch.castleridge.javals.ast.ConstructorCallStmt;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.ContinueStmt;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.DefaultLabel;
import ch.castleridge.javals.ast.DoWhileStmt;
import ch.castleridge.javals.ast.EmptyArrays;
import ch.castleridge.javals.ast.EmptyStmt;
import ch.castleridge.javals.ast.EnumConstantDecl;
import ch.castleridge.javals.ast.EnumConstantSymbol;
import ch.castleridge.javals.ast.ErroneousExpr;
import ch.castleridge.javals.ast.ErroneousStmt;
import ch.castleridge.javals.ast.ExprStmt;
import ch.castleridge.javals.ast.FieldDecl;
import ch.castleridge.javals.ast.FieldSymbol;
import ch.castleridge.javals.ast.ForEachStmt;
import ch.castleridge.javals.ast.ForStmt;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.IfStmt;
import ch.castleridge.javals.ast.ImportDecl;
import ch.castleridge.javals.ast.InitializerDecl;
import ch.castleridge.javals.ast.InstanceOfExpr;
import ch.castleridge.javals.ast.JType;
import ch.castleridge.javals.ast.LabeledStmt;
import ch.castleridge.javals.ast.LambdaExpr;
import ch.castleridge.javals.ast.LiteralExpr;
import ch.castleridge.javals.ast.LocalDeclStmt;
import ch.castleridge.javals.ast.LocalSymbol;
import ch.castleridge.javals.ast.LocalTypeStmt;
import ch.castleridge.javals.ast.MemberRefExpr;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.MethodSymbol;
import ch.castleridge.javals.ast.Modifier;
import ch.castleridge.javals.ast.NameExpr;
import ch.castleridge.javals.ast.NewArrayExpr;
import ch.castleridge.javals.ast.NewExpr;
import ch.castleridge.javals.ast.Node;
import ch.castleridge.javals.ast.PackageDecl;
import ch.castleridge.javals.ast.PackageSymbol;
import ch.castleridge.javals.ast.ParamDecl;
import ch.castleridge.javals.ast.ParameterizedTypeNode;
import ch.castleridge.javals.ast.PrimitiveTypeNode;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.RecordComponentSymbol;
import ch.castleridge.javals.ast.ReturnStmt;
import ch.castleridge.javals.ast.Select;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.SuperExpr;
import ch.castleridge.javals.ast.SwitchArm;
import ch.castleridge.javals.ast.SwitchExpr;
import ch.castleridge.javals.ast.SwitchStmt;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.ast.SynchronizedStmt;
import ch.castleridge.javals.ast.ThisExpr;
import ch.castleridge.javals.ast.ThrowStmt;
import ch.castleridge.javals.ast.TryStmt;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.ast.TypeDeclKind;
import ch.castleridge.javals.ast.TypeName;
import ch.castleridge.javals.ast.TypeNode;
import ch.castleridge.javals.ast.TypeParamDecl;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.TypeVarSymbol;
import ch.castleridge.javals.ast.UnaryExpr;
import ch.castleridge.javals.ast.VarFragment;
import ch.castleridge.javals.ast.VarTypeNode;
import ch.castleridge.javals.ast.VoidTypeNode;
import ch.castleridge.javals.ast.WhileStmt;
import ch.castleridge.javals.ast.WildcardTypeNode;
import ch.castleridge.javals.ast.YieldStmt;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeRef;

final class EcjAstLowerer {

    /** True when this ECJ build exposes {@link TypeDeclaration#RECORD_DECL}. */
    private static final boolean HAS_RECORD_DECL = detectRecordDecl();

    private final CompilationUnitDeclaration unit;
    private final String uri;
    private final String source;
    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<String, String> sourceJarByBinaryJar;
    private final Map<Binding, Symbol> interned = new IdentityHashMap<>();
    private final Map<String, TypeSymbol> typesByJvm = new HashMap<>();
    private final Map<String, JType.Declared> declaredByJvm = new HashMap<>();
    private final Map<String, Optional<String>> originByJvm = new HashMap<>();
    /** Lazily filled set of JVM names declared in this CU (incl. nested). */
    private Set<String> declaredJvmNames;

    private EcjAstLowerer(CompilationUnitDeclaration unit,
                          String uri,
                          String source,
                          Index index,
                          ClasspathOrder classpath,
                          Map<String, String> sourceJarByBinaryJar) {
        this.unit = unit;
        this.uri = uri == null ? "" : uri;
        this.source = source == null ? "" : source;
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    static CompilationUnit lower(CompilationUnitDeclaration unit,
                                 String uri,
                                 String source,
                                 Index index,
                                 ClasspathOrder classpath,
                                 Map<String, String> sourceJarByBinaryJar) {
        String text = source == null ? "" : source;
        String u = uri == null ? "" : uri;
        if (unit == null) {
            return new CompilationUnit(new SourceFile(u, text), null,
                    EmptyArrays.IMPORT_DECL, EmptyArrays.TYPE_DECL, null,
                    new SourceRange(0, text.length()));
        }
        return new EcjAstLowerer(unit, u, text, index, classpath, sourceJarByBinaryJar).lowerUnit();
    }

    static CompilationUnit diet(CompilationUnitDeclaration unit, String uri, String source) {
        return new EcjAstLowerer(unit, uri == null ? "" : uri, source, null,
                ClasspathOrder.UNRESTRICTED, Map.of()).lowerUnit();
    }

    private CompilationUnit lowerUnit() {
        PackageDecl pkg = unit.currentPackage == null ? null
                : new PackageDecl(EmptyArrays.ANNOTATION, importNames(unit.currentPackage), range(unit.currentPackage));
        ImportDecl[] imports;
        if (unit.imports == null || unit.imports.length == 0) {
            imports = EmptyArrays.IMPORT_DECL;
        } else {
            imports = new ImportDecl[unit.imports.length];
            for (int i = 0; i < unit.imports.length; i++) {
                imports[i] = lowerImport(unit.imports[i]);
            }
        }
        TypeDecl[] types;
        if (unit.types == null || unit.types.length == 0) {
            types = EmptyArrays.TYPE_DECL;
        } else {
            types = new TypeDecl[unit.types.length];
            for (int i = 0; i < unit.types.length; i++) {
                types[i] = lowerType(unit.types[i]);
            }
        }
        return new CompilationUnit(new SourceFile(uri, source), pkg, imports, types, null,
                new SourceRange(0, source.length()));
    }

    private ImportDecl lowerImport(ImportReference tree) {
        Identifier[] names = importNames(tree);
        boolean onDemand = (tree.bits & ASTNode.OnDemand) != 0;
        return new ImportDecl(tree.isStatic(), onDemand, names, range(tree));
    }

    private Identifier[] importNames(ImportReference tree) {
        if (tree.tokens == null || tree.tokens.length == 0) return EmptyArrays.IDENTIFIER;
        int n = tree.tokens.length;
        String[] nameStrs = new String[n];
        SourceRange[] ranges = new SourceRange[n];
        for (int i = 0; i < n; i++) {
            long pos = tree.sourcePositions == null || i >= tree.sourcePositions.length
                    ? pack(tree.sourceStart, tree.sourceEnd) : tree.sourcePositions[i];
            nameStrs[i] = new String(tree.tokens[i]);
            ranges[i] = posRange(pos);
        }
        Symbol[] symbols = importSymbols(nameStrs, tree.isStatic(), (tree.bits & ASTNode.OnDemand) != 0);
        Identifier[] out = new Identifier[n];
        for (int i = 0; i < n; i++) {
            out[i] = new Identifier(nameStrs[i], ranges[i], symbols[i]);
        }
        return out;
    }

    private Symbol[] importSymbols(String[] names, boolean staticImport, boolean onDemand) {
        Symbol[] symbols = new Symbol[names.length];
        if (names.length == 0) return symbols;
        String jvm = "";
        TypeSymbol type = null;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            boolean last = i == names.length - 1;
            String slash = jvm.isEmpty() ? name : jvm + "/" + name;
            String nested = jvm.isEmpty() ? name : jvm + "$" + name;
            TypeSymbol found = typeSymbolForJvm(nested);
            if (found == null) found = typeSymbolForJvm(slash);
            if (found != null) {
                symbols[i] = found;
                type = found;
                jvm = found.jvmBinaryName().replace('.', '/');
                continue;
            }
            if (type != null && last && staticImport && !onDemand) {
                symbols[i] = staticImportMember(type, name);
                continue;
            }
            String qualified = jvm.isEmpty() ? name : jvm.replace('/', '.') + "." + name;
            symbols[i] = new PackageSymbol(qualified, SymbolKey.local(name));
            jvm = slash;
        }
        return symbols;
    }

    /**
     * Bind a single static-import member from the owner's indexed fields/methods.
     * Prefers a field; methods use a name-only key covering all overloads.
     */
    private Symbol staticImportMember(TypeSymbol owner, String name) {
        if (owner == null || name == null || name.isEmpty()) return null;
        String jvm = owner.jvmBinaryName().replace('.', '/');
        TypeEntry entry = indexedEntry(jvm);
        if (entry == null) return null;
        Optional<String> originOpt = origin(jvm);
        if (originOpt.isEmpty()) return null;
        String originUri = originOpt.get();
        String ownerBinary = owner.jvmBinaryName();

        for (FieldEntry field : entry.fields()) {
            if (!name.equals(field.name())) continue;
            if ((field.modifiers() & ClassFileConstants.AccStatic) == 0) continue;
            SymbolKey key = SymbolKey.of(
                    "F:" + originUri + "|" + ownerBinary + "#" + name, name, originUri);
            return new FieldSymbol(name, indexJType(field.type()), owner, key, false);
        }

        MethodEntry first = null;
        for (MethodEntry method : entry.methods()) {
            if (!name.equals(method.name())) continue;
            if ((method.modifiers() & ClassFileConstants.AccStatic) == 0) continue;
            first = method;
            break;
        }
        if (first == null) return null;

        ch.castleridge.javals.indexing.model.ParameterEntry[] parameters = first.parameters();
        JType[] params = parameters.length == 0
                ? EmptyArrays.JTYPE
                : new JType[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            params[i] = indexJType(parameters[i].type());
        }
        return new MethodSymbol(name, indexJType(first.returnType()), params, owner,
                SymbolKey.methodName(originUri, ownerBinary, name), false, false);
    }

    private static JType indexJType(Type type) {
        if (type == null) return JType.ERROR;
        if (type instanceof Type.Annotated annotated) return indexJType(annotated.inner());
        if (type instanceof Type.Primitive primitive) {
            return switch (primitive) {
                case VOID -> JType.VOID;
                case BOOLEAN -> JType.Primitive.BOOLEAN;
                case BYTE -> JType.Primitive.BYTE;
                case SHORT -> JType.Primitive.SHORT;
                case CHAR -> JType.Primitive.CHAR;
                case INT -> JType.Primitive.INT;
                case LONG -> JType.Primitive.LONG;
                case FLOAT -> JType.Primitive.FLOAT;
                case DOUBLE -> JType.Primitive.DOUBLE;
            };
        }
        if (type instanceof Type.Array array) return JType.array(indexJType(array.element()));
        if (type instanceof Type.TypeVariable tv) return new JType.TypeVar(tv.name());
        if (type instanceof Type.Wildcard wildcard) {
            return switch (wildcard.kind()) {
                case UNBOUNDED -> JType.Wildcard.unbounded();
                case EXTENDS -> new JType.Wildcard(JType.Wildcard.BoundKind.EXTENDS, indexJType(wildcard.bound()));
                case SUPER -> new JType.Wildcard(JType.Wildcard.BoundKind.SUPER, indexJType(wildcard.bound()));
            };
        }
        if (type instanceof Type.Parameterized parameterized) {
            String raw = typeRefJvm(parameterized.raw());
            if (raw == null) return JType.ERROR;
            Type[] args = parameterized.typeArgs();
            if (args.length == 0) return JType.Declared.of(raw);
            JType[] jArgs = new JType[args.length];
            for (int i = 0; i < args.length; i++) jArgs[i] = indexJType(args[i]);
            return new JType.Declared(raw, jArgs);
        }
        if (type instanceof TypeRef ref) {
            String jvm = typeRefJvm(ref);
            return jvm == null ? JType.ERROR : JType.Declared.of(jvm);
        }
        return JType.ERROR;
    }

    private static String typeRefJvm(TypeRef ref) {
        if (ref instanceof TypeRef.Resolved resolved) return resolved.jvmBinaryName();
        return null;
    }

    private TypeSymbol typeSymbolForJvm(String jvm) {
        if (jvm == null || jvm.isEmpty() || index == null) return null;
        TypeSymbol cached = typesByJvm.get(jvm);
        if (cached != null) return cached;
        TypeEntry entry = classpath.pick(index.getAll(jvm), TypeEntry::sourceUri);
        if (entry == null) return null;
        Optional<String> origin = origin(jvm);
        if (origin.isEmpty()) return null;
        int cut = Math.max(jvm.lastIndexOf('/'), jvm.lastIndexOf('$'));
        String simple = cut < 0 ? jvm : jvm.substring(cut + 1);
        TypeSymbol symbol = new TypeSymbol(TypeDeclKind.CLASS, jvm.replace('/', '.'),
                new SymbolKey("T:" + origin.get() + "|" + jvm.replace('/', '.'), simple, false, origin),
                EmptyArrays.TYPE_VAR_SYMBOL);
        typesByJvm.put(jvm, symbol);
        return symbol;
    }

    private TypeDecl lowerType(TypeDeclaration tree) {
        TypeDeclKind kind = typeKind(tree);
        TypeSymbol symbol = typeSymbol(tree.binding);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        TypeParamDecl[] typeParams = lowerTypeParams(tree.typeParameters);
        TypeNode superclass = tree.superclass == null ? null : lowerTypeRef(tree.superclass);
        TypeNode[] interfaces = lowerTypeRefs(tree.superInterfaces);
        TypeNode[] permits = lowerTypeRefs(tree.permittedTypes);
        RecordComponentDecl[] components;
        if (tree.recordComponents == null || tree.recordComponents.length == 0) {
            components = EmptyArrays.RECORD_COMPONENT;
        } else {
            components = new RecordComponentDecl[tree.recordComponents.length];
            for (int i = 0; i < tree.recordComponents.length; i++) {
                components[i] = lowerRecordComponent(tree.recordComponents[i], symbol);
            }
        }
        Declaration[] members = lowerMembers(tree, symbol, kind);
        return new TypeDecl(kind, mods(tree.modifiers), annos(tree.annotations), name, typeParams,
                superclass, interfaces, permits, components, members, symbol, range(tree));
    }

    private Declaration[] lowerMembers(TypeDeclaration tree, TypeSymbol symbol, TypeDeclKind kind) {
        int nestedCount = tree.memberTypes == null ? 0 : tree.memberTypes.length;
        int fieldCount = tree.fields == null ? 0 : tree.fields.length;
        int methodCount = 0;
        if (tree.methods != null) {
            for (AbstractMethodDeclaration method : tree.methods) {
                if (method instanceof Clinit) continue;
                if ((method.modifiers & ClassFileConstants.AccSynthetic) != 0) continue;
                methodCount++;
            }
        }
        int total = nestedCount + fieldCount + methodCount;
        if (total == 0) return EmptyArrays.DECLARATION;
        Declaration[] members = new Declaration[total];
        int i = 0;
        if (tree.memberTypes != null) {
            for (TypeDeclaration nested : tree.memberTypes) {
                members[i++] = lowerType(nested);
            }
        }
        if (tree.fields != null) {
            for (FieldDeclaration field : tree.fields) {
                members[i++] = lowerField(field, symbol, kind);
            }
        }
        if (tree.methods != null) {
            for (AbstractMethodDeclaration method : tree.methods) {
                if (method instanceof Clinit) continue;
                if ((method.modifiers & ClassFileConstants.AccSynthetic) != 0) continue;
                members[i++] = lowerMethod(method, symbol, kind);
            }
        }
        return members;
    }

    private RecordComponentDecl lowerRecordComponent(
            org.eclipse.jdt.internal.compiler.ast.RecordComponent tree, TypeSymbol owner) {
        RecordComponentSymbol symbol = recordComponentSymbol(tree.binding, owner);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        return new RecordComponentDecl(annos(tree.annotations), lowerTypeRef(tree.type), name,
                tree.type instanceof ArrayTypeReference, symbol, range(tree));
    }

    private Declaration lowerField(FieldDeclaration tree, TypeSymbol owner, TypeDeclKind ownerKind) {
        if (tree instanceof Initializer init) {
            return new InitializerDecl(init.isStatic(), lowerBlock(init.block), range(tree));
        }
        if (ownerKind == TypeDeclKind.ENUM && tree.initialization instanceof AllocationExpression) {
            EnumConstantSymbol symbol = enumSymbol(tree.binding, owner);
            Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
            ch.castleridge.javals.ast.Expression[] args = EmptyArrays.EXPRESSION;
            TypeDecl body = null;
            if (tree.initialization instanceof QualifiedAllocationExpression alloc && alloc.anonymousType != null) {
                args = mapExprs(alloc.arguments);
                body = lowerType(alloc.anonymousType);
            } else if (tree.initialization instanceof AllocationExpression alloc) {
                args = mapExprs(alloc.arguments);
            }
            return new EnumConstantDecl(annos(tree.annotations), name, args, body, symbol, range(tree));
        }
        FieldSymbol symbol = fieldSymbol(tree.binding, owner);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        VarFragment fragment = new VarFragment(name, 0,
                tree.initialization == null ? null : lowerExpr(tree.initialization), symbol, range(tree));
        return new FieldDecl(mods(tree.modifiers), annos(tree.annotations), lowerTypeRef(tree.type),
                new VarFragment[] { fragment }, range(tree));
    }

    private Declaration lowerMethod(AbstractMethodDeclaration tree, TypeSymbol owner, TypeDeclKind ownerKind) {
        MethodSymbol symbol = methodSymbol(tree.binding, owner);
        boolean constructor = tree.isConstructor();
        String raw = str(tree.selector);
        String display = constructor && owner != null ? owner.name() : raw;
        Identifier name = new Identifier(display, nameRange(tree.selector, tree.sourceStart, tree.sourceEnd), symbol);
        TypeParamDecl[] typeParams = lowerTypeParams(tree.typeParameters());
        ParamDecl[] params;
        if (tree.arguments == null || tree.arguments.length == 0) {
            params = EmptyArrays.PARAM_DECL;
        } else {
            params = new ParamDecl[tree.arguments.length];
            boolean varargs = tree.binding != null && tree.binding.isVarargs();
            for (int i = 0; i < tree.arguments.length; i++) {
                params[i] = lowerParam(tree.arguments[i], i == tree.arguments.length - 1 && varargs);
            }
        }
        TypeNode[] thrown = lowerTypeRefs(tree.thrownExceptions);
        ch.castleridge.javals.ast.Block body = tree.statements == null ? null : statementsBlock(tree);
        if (constructor && (tree.modifiers & ExtraCompilerModifiers.AccCompactConstructor) != 0
                && ownerKind == TypeDeclKind.RECORD) {
            return new CompactConstructorDecl(mods(tree.modifiers), annos(tree.annotations), name, body, symbol, range(tree));
        }
        if (constructor) {
            return new ConstructorDecl(mods(tree.modifiers), annos(tree.annotations), typeParams, name,
                    null, params, thrown, body, symbol, range(tree));
        }
        TypeNode returnType = tree instanceof MethodDeclaration method && method.returnType != null
                ? lowerTypeRef(method.returnType) : new VoidTypeNode(null, SourceRange.NONE);
        return new MethodDecl(mods(tree.modifiers), annos(tree.annotations), typeParams, returnType, name,
                null, params, thrown, body, symbol, range(tree));
    }

    private ParamDecl lowerParam(Argument tree, boolean varargs) {
        LocalSymbol symbol = localSymbol(tree.binding);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        return new ParamDecl(mods(tree.modifiers), annos(tree.annotations), lowerTypeRef(tree.type),
                name, varargs, symbol, range(tree));
    }

    private TypeParamDecl[] lowerTypeParams(TypeParameter[] params) {
        if (params == null || params.length == 0) return EmptyArrays.TYPE_PARAM;
        TypeParamDecl[] out = new TypeParamDecl[params.length];
        for (int i = 0; i < params.length; i++) {
            TypeParameter param = params[i];
            TypeVarSymbol symbol = typeVarSymbol(param.binding);
            Identifier name = new Identifier(str(param.name), nameRange(param.name, param.sourceStart, param.sourceEnd), symbol);
            out[i] = new TypeParamDecl(EmptyArrays.ANNOTATION, name, lowerTypeRefs(param.bounds), symbol, range(param));
        }
        return out;
    }

    private ch.castleridge.javals.ast.Block statementsBlock(AbstractMethodDeclaration tree) {
        boolean hasCtor = tree instanceof ConstructorDeclaration ctor && ctor.constructorCall != null;
        int stmtCount = tree.statements == null ? 0 : tree.statements.length;
        int n = (hasCtor ? 1 : 0) + stmtCount;
        if (n == 0) {
            return new ch.castleridge.javals.ast.Block(EmptyArrays.STATEMENT, range(tree));
        }
        ch.castleridge.javals.ast.Statement[] stmts = new ch.castleridge.javals.ast.Statement[n];
        int i = 0;
        if (hasCtor) {
            stmts[i++] = lowerConstructorCall(((ConstructorDeclaration) tree).constructorCall);
        }
        if (tree.statements != null) {
            for (Statement stmt : tree.statements) {
                stmts[i++] = lowerStmt(stmt);
            }
        }
        return new ch.castleridge.javals.ast.Block(stmts, range(tree));
    }

    private ch.castleridge.javals.ast.Block lowerBlock(Block tree) {
        if (tree == null) return new ch.castleridge.javals.ast.Block(EmptyArrays.STATEMENT, SourceRange.NONE);
        if (tree.statements == null || tree.statements.length == 0) {
            return new ch.castleridge.javals.ast.Block(EmptyArrays.STATEMENT, range(tree));
        }
        ch.castleridge.javals.ast.Statement[] stmts = new ch.castleridge.javals.ast.Statement[tree.statements.length];
        for (int i = 0; i < tree.statements.length; i++) {
            stmts[i] = lowerStmt(tree.statements[i]);
        }
        return new ch.castleridge.javals.ast.Block(stmts, range(tree));
    }

    private ch.castleridge.javals.ast.Statement lowerStmt(Statement tree) {
        if (tree == null) return new EmptyStmt(SourceRange.NONE);
        SourceRange r = range(tree);
        // Common first: expression statements, locals, blocks, control flow
        if (tree instanceof Expression expr) return new ExprStmt(lowerExpr(expr), r);
        if (tree instanceof LocalDeclaration local) return lowerLocal(local);
        if (tree instanceof Block block) return lowerBlock(block);
        if (tree instanceof ReturnStatement ret) {
            return new ReturnStmt(ret.expression == null ? null : lowerExpr(ret.expression), r);
        }
        if (tree instanceof IfStatement iff) {
            return new IfStmt(lowerExpr(iff.condition), lowerStmt(iff.thenStatement),
                    iff.elseStatement == null ? null : lowerStmt(iff.elseStatement), r);
        }
        if (tree instanceof ExplicitConstructorCall call) return lowerConstructorCall(call);
        if (tree instanceof EmptyStatement) return new EmptyStmt(r);
        if (tree instanceof WhileStatement loop) {
            return new WhileStmt(lowerExpr(loop.condition), lowerStmt(loop.action), r);
        }
        if (tree instanceof ForStatement loop) return lowerFor(loop);
        if (tree instanceof ForeachStatement loop) return lowerForeach(loop);
        if (tree instanceof DoStatement loop) {
            return new DoWhileStmt(lowerStmt(loop.action), lowerExpr(loop.condition), r);
        }
        if (tree instanceof TryStatement tryStmt) return lowerTry(tryStmt);
        if (tree instanceof ThrowStatement thr) return new ThrowStmt(lowerExpr(thr.exception), r);
        if (tree instanceof BreakStatement brk) {
            return new BreakStmt(brk.label == null ? null : new Identifier(str(brk.label), SourceRange.NONE, null), r);
        }
        if (tree instanceof ContinueStatement cont) {
            return new ContinueStmt(cont.label == null ? null : new Identifier(str(cont.label), SourceRange.NONE, null), r);
        }
        if (tree instanceof SwitchStatement sw) {
            return new SwitchStmt(lowerExpr(sw.expression), lowerSwitchArms(sw.statements), r);
        }
        if (tree instanceof SynchronizedStatement sync) {
            return new SynchronizedStmt(lowerExpr(sync.expression), lowerBlock(sync.block), r);
        }
        if (tree instanceof LabeledStatement labeled) {
            return new LabeledStmt(new Identifier(str(labeled.label), SourceRange.NONE, null),
                    lowerStmt(labeled.statement), r);
        }
        if (tree instanceof AssertStatement asrt) {
            return new AssertStmt(lowerExpr(asrt.assertExpression),
                    asrt.exceptionArgument == null ? null : lowerExpr(asrt.exceptionArgument), r);
        }
        if (tree instanceof YieldStatement yield) return new YieldStmt(lowerExpr(yield.expression), r);
        if (tree instanceof TypeDeclaration nested) return new LocalTypeStmt(lowerType(nested), r);
        return new ErroneousStmt(EmptyArrays.NODE, r);
    }

    private ConstructorCallStmt lowerConstructorCall(ExplicitConstructorCall tree) {
        boolean isSuper = tree.isSuperAccess();
        return new ConstructorCallStmt(isSuper,
                tree.qualification == null ? null : lowerExpr(tree.qualification),
                EmptyArrays.TYPE_NODE, mapExprs(tree.arguments), methodSymbol(tree.binding, null), range(tree));
    }

    private ForStmt lowerFor(ForStatement tree) {
        Node[] init = EmptyArrays.NODE;
        if (tree.initializations != null && tree.initializations.length > 0) {
            int count = 0;
            for (Statement stmt : tree.initializations) {
                if (stmt instanceof LocalDeclaration || stmt instanceof Expression) count++;
            }
            if (count > 0) {
                init = new Node[count];
                int i = 0;
                for (Statement stmt : tree.initializations) {
                    if (stmt instanceof LocalDeclaration local) init[i++] = lowerLocal(local);
                    else if (stmt instanceof Expression expr) init[i++] = lowerExpr(expr);
                }
            }
        }
        ch.castleridge.javals.ast.Expression[] update = EmptyArrays.EXPRESSION;
        if (tree.increments != null && tree.increments.length > 0) {
            int count = 0;
            for (Statement stmt : tree.increments) {
                if (stmt instanceof Expression) count++;
            }
            if (count > 0) {
                update = new ch.castleridge.javals.ast.Expression[count];
                int i = 0;
                for (Statement stmt : tree.increments) {
                    if (stmt instanceof Expression expr) update[i++] = lowerExpr(expr);
                }
            }
        }
        return new ForStmt(init, tree.condition == null ? null : lowerExpr(tree.condition),
                update, lowerStmt(tree.action), range(tree));
    }

    private ForEachStmt lowerForeach(ForeachStatement tree) {
        return new ForEachStmt(lowerLocal(tree.elementVariable), lowerExpr(tree.collection),
                lowerStmt(tree.action), range(tree));
    }

    private TryStmt lowerTry(TryStatement tree) {
        Node[] resources = EmptyArrays.NODE;
        if (tree.resources != null && tree.resources.length > 0) {
            int count = 0;
            for (Statement resource : tree.resources) {
                if (resource instanceof LocalDeclaration || resource instanceof Expression) count++;
            }
            if (count > 0) {
                resources = new Node[count];
                int i = 0;
                for (Statement resource : tree.resources) {
                    if (resource instanceof LocalDeclaration local) resources[i++] = lowerLocal(local);
                    else if (resource instanceof Expression expr) resources[i++] = lowerExpr(expr);
                }
            }
        }
        CatchClause[] catches;
        if (tree.catchArguments == null || tree.catchArguments.length == 0) {
            catches = EmptyArrays.CATCH_CLAUSE;
        } else {
            catches = new CatchClause[tree.catchArguments.length];
            for (int i = 0; i < tree.catchArguments.length; i++) {
                catches[i] = new CatchClause(lowerParam(tree.catchArguments[i], false),
                        lowerBlock(tree.catchBlocks[i]), range(tree.catchArguments[i]));
            }
        }
        return new TryStmt(resources, lowerBlock(tree.tryBlock), catches,
                tree.finallyBlock == null ? null : lowerBlock(tree.finallyBlock), range(tree));
    }

    private LocalDeclStmt lowerLocal(LocalDeclaration tree) {
        LocalSymbol symbol = localSymbol(tree.binding);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        VarFragment fragment = new VarFragment(name, 0,
                tree.initialization == null ? null : lowerExpr(tree.initialization), symbol, range(tree));
        TypeNode type;
        if (tree.type == null) {
            type = new VarTypeNode(symbol != null ? symbol.type() : null, SourceRange.NONE);
        } else {
            type = lowerTypeRef(tree.type);
            if (type instanceof VarTypeNode && symbol != null) {
                type = new VarTypeNode(symbol.type(), type.range());
            }
        }
        return new LocalDeclStmt(mods(tree.modifiers), annos(tree.annotations), type,
                new VarFragment[] { fragment }, range(tree));
    }

    private SwitchArm[] lowerSwitchArms(Statement[] statements) {
        if (statements == null || statements.length == 0) return EmptyArrays.SWITCH_ARM;
        ArrayList<SwitchArm> arms = new ArrayList<>();
        ArrayList<CaseLabel> labels = new ArrayList<>();
        ArrayList<ch.castleridge.javals.ast.Statement> body = new ArrayList<>();
        int start = -1;
        for (Statement stmt : statements) {
            if (stmt instanceof CaseStatement cse) {
                if (!labels.isEmpty() || !body.isEmpty()) {
                    arms.add(new SwitchArm(
                            toCaseLabels(labels), false, null, toStatements(body),
                            start < 0 ? range(stmt) : new SourceRange(start, inclusiveEnd(stmt.sourceEnd))));
                    labels = new ArrayList<>();
                    body = new ArrayList<>();
                }
                start = cse.sourceStart;
                if (cse.constantExpressions == null || cse.constantExpressions.length == 0) {
                    labels.add(new DefaultLabel(range(cse)));
                } else {
                    for (Expression expr : cse.constantExpressions) {
                        labels.add(new ConstantLabel(lowerExpr(expr), range(expr)));
                    }
                }
            } else {
                body.add(lowerStmt(stmt));
            }
        }
        if (!labels.isEmpty() || !body.isEmpty()) {
            arms.add(new SwitchArm(
                    toCaseLabels(labels), false, null, toStatements(body),
                    start < 0 ? SourceRange.NONE : new SourceRange(start, source.length())));
        }
        return arms.isEmpty() ? EmptyArrays.SWITCH_ARM : arms.toArray(new SwitchArm[arms.size()]);
    }

    private static CaseLabel[] toCaseLabels(ArrayList<CaseLabel> labels) {
        return labels.isEmpty() ? EmptyArrays.CASE_LABEL : labels.toArray(new CaseLabel[labels.size()]);
    }

    private static ch.castleridge.javals.ast.Statement[] toStatements(
            ArrayList<ch.castleridge.javals.ast.Statement> body) {
        return body.isEmpty() ? EmptyArrays.STATEMENT
                : body.toArray(new ch.castleridge.javals.ast.Statement[body.size()]);
    }

    private ch.castleridge.javals.ast.Expression lowerExpr(Expression tree) {
        if (tree == null) return null;
        SourceRange r = range(tree);
        JType type = jtype(tree.resolvedType);
        // Hot path first (profile-ordered)
        if (tree instanceof MessageSend send) {
            return lowerCall(send);
        }
        if (tree instanceof AllocationExpression alloc) {
            return lowerNew(alloc);
        }
        if (tree instanceof SingleNameReference name) {
            Identifier ident = new Identifier(str(name.token), r, symbolOf(name.binding));
            return new NameExpr(ident, type, r);
        }
        if (tree instanceof PrefixExpression prefix) {
            int op = (prefix.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            return new UnaryExpr(unaryOp(op, false), lowerExpr(prefix.lhs), type, r);
        }
        if (tree instanceof PostfixExpression postfix) {
            int op = (postfix.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            return new UnaryExpr(unaryOp(op, true), lowerExpr(postfix.lhs), type, r);
        }
        if (tree instanceof Assignment assign) {
            AssignExpr.Op op = assign instanceof CompoundAssignment compound
                    ? assignOp((compound.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT)
                    : AssignExpr.Op.ASSIGN;
            return new AssignExpr(op, lowerExpr(assign.lhs), lowerExpr(assign.expression), type, r);
        }
        if (tree instanceof EqualExpression equal) {
            return new BinaryExpr(equalOp(equal), lowerExpr(equal.left), lowerExpr(equal.right), type, r);
        }
        if (tree instanceof BinaryExpression binary) {
            int op = (binary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            return new BinaryExpr(binaryOp(op), lowerExpr(binary.left), lowerExpr(binary.right), type, r);
        }
        if (tree instanceof UnaryExpression unary) {
            int op = (unary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            return new UnaryExpr(unaryOp(op, false), lowerExpr(unary.expression), type, r);
        }
        if (tree instanceof Literal literal) {
            return lowerLiteral(literal, r);
        }
        if (tree instanceof FieldReference field) {
            Identifier ident = new Identifier(str(field.token), namePos(field.nameSourcePosition), symbolOf(field.binding));
            return new Select(lowerExpr(field.receiver), ident, type, r);
        }
        if (tree instanceof QualifiedNameReference qual) {
            return lowerQualifiedName(qual);
        }
        if (tree instanceof ArrayAllocationExpression array) {
            return lowerNewArray(array);
        }
        if (tree instanceof ArrayInitializer init) {
            return new ArrayInitExpr(mapExprs(init.expressions), type, r);
        }
        if (tree instanceof ArrayReference access) {
            return new ArrayAccessExpr(lowerExpr(access.receiver), lowerExpr(access.position), type, r);
        }
        if (tree instanceof CastExpression cast) {
            return new CastExpr(lowerTypeRef(cast.type), lowerExpr(cast.expression), type, r);
        }
        if (tree instanceof InstanceOfExpression io) {
            return new InstanceOfExpr(lowerExpr(io.expression), lowerTypeRef(io.type), null, type, r);
        }
        if (tree instanceof ThisReference thisRef) {
            TypeNode qual = tree instanceof QualifiedThisReference q ? lowerTypeRef(q.qualification) : null;
            return new ThisExpr(qual, type, r);
        }
        if (tree instanceof SuperReference) {
            return new SuperExpr(null, type, r);
        }
        if (tree instanceof ClassLiteralAccess cl) {
            return new ClassLiteralExpr(lowerTypeRef(cl.type), type, r);
        }
        if (tree instanceof LambdaExpression lambda) {
            return lowerLambda(lambda);
        }
        if (tree instanceof ReferenceExpression ref) {
            return lowerMemberRef(ref);
        }
        if (tree instanceof SwitchExpression sw) {
            return new SwitchExpr(lowerExpr(sw.expression), lowerSwitchArms(sw.statements), type, r);
        }
        if (tree instanceof org.eclipse.jdt.internal.compiler.ast.ConditionalExpression cond) {
            return new ConditionalExpr(lowerExpr(cond.condition), lowerExpr(cond.valueIfTrue),
                    lowerExpr(cond.valueIfFalse), type, r);
        }
        if (tree instanceof TypeReference typeRef) {
            TypeNode typeNode = lowerTypeRef(typeRef);
            Identifier name;
            if (typeNode instanceof TypeName tn && tn.simpleName() != null) {
                Identifier simple = tn.simpleName();
                name = new Identifier(simple.name(), simple.range(), simple.symbol());
            } else {
                name = new Identifier(typeRef.toString(), r, null);
            }
            return new NameExpr(name, type, r);
        }
        return new ErroneousExpr(EmptyArrays.NODE, type, r);
    }

    private ch.castleridge.javals.ast.Expression lowerQualifiedName(QualifiedNameReference tree) {
        ch.castleridge.javals.ast.Expression current = null;
        char[][] tokens = tree.tokens;
        long[] positions = tree.sourcePositions;
        Binding[] others = tree.otherBindings;
        for (int i = 0; i < tokens.length; i++) {
            SourceRange nr = positions == null || i >= positions.length ? range(tree) : posRange(positions[i]);
            Binding binding = i == tokens.length - 1 ? tree.binding
                    : (others != null && i < others.length ? others[i] : null);
            Identifier ident = new Identifier(new String(tokens[i]), nr, symbolOf(binding));
            JType type = i == tokens.length - 1 ? jtype(tree.resolvedType) : null;
            current = current == null
                    ? new NameExpr(ident, type, nr)
                    : new Select(current, ident, type, span(current.range(), nr));
        }
        return current;
    }

    private CallExpr lowerCall(MessageSend tree) {
        Identifier name = new Identifier(str(tree.selector), namePos(tree.nameSourcePosition), symbolOf(tree.binding));
        return new CallExpr(tree.receiver == null || tree.receiver.isImplicitThis() ? null : lowerExpr(tree.receiver),
                name, EmptyArrays.TYPE_NODE, mapExprs(tree.arguments), jtype(tree.resolvedType), range(tree));
    }

    private NewExpr lowerNew(AllocationExpression tree) {
        TypeDecl body = null;
        if (tree instanceof QualifiedAllocationExpression q && q.anonymousType != null) {
            body = lowerType(q.anonymousType);
        }
        MethodSymbol constructor = methodSymbol(tree.binding, null);
        TypeNode type = lowerTypeRef(tree.type, constructor);
        return new NewExpr(
                tree instanceof QualifiedAllocationExpression q ? lowerExpr(q.enclosingInstance()) : null,
                type, EmptyArrays.TYPE_NODE, mapExprs(tree.arguments), body,
                constructor, jtype(tree.resolvedType), range(tree));
    }

    private NewArrayExpr lowerNewArray(ArrayAllocationExpression tree) {
        ArrayInitExpr init = tree.initializer == null ? null
                : new ArrayInitExpr(mapExprs(tree.initializer.expressions),
                        jtype(tree.initializer.resolvedType), range(tree.initializer));
        return new NewArrayExpr(lowerTypeRef(tree.type), mapExprs(tree.dimensions), init,
                jtype(tree.resolvedType), range(tree));
    }

    private LiteralExpr lowerLiteral(Literal tree, SourceRange r) {
        Constant constant = tree.constant;
        if (constant == null || constant == Constant.NotAConstant) {
            if (tree instanceof org.eclipse.jdt.internal.compiler.ast.NullLiteral) {
                return new LiteralExpr(LiteralExpr.Kind.NULL, null, slice(r), null, r);
            }
            return new LiteralExpr(LiteralExpr.Kind.STRING, tree.toString(), slice(r), null, r);
        }
        return switch (constant.typeID()) {
            case TypeIds.T_int -> new LiteralExpr(LiteralExpr.Kind.INT, constant.intValue(), slice(r), null, r);
            case TypeIds.T_long -> new LiteralExpr(LiteralExpr.Kind.LONG, constant.longValue(), slice(r), null, r);
            case TypeIds.T_float -> new LiteralExpr(LiteralExpr.Kind.FLOAT, constant.floatValue(), slice(r), null, r);
            case TypeIds.T_double -> new LiteralExpr(LiteralExpr.Kind.DOUBLE, constant.doubleValue(), slice(r), null, r);
            case TypeIds.T_char -> new LiteralExpr(LiteralExpr.Kind.CHAR, constant.charValue(), slice(r), null, r);
            case TypeIds.T_boolean -> new LiteralExpr(LiteralExpr.Kind.BOOLEAN, constant.booleanValue(), slice(r), null, r);
            case TypeIds.T_JavaLangString -> new LiteralExpr(LiteralExpr.Kind.STRING, constant.stringValue(), slice(r), null, r);
            default -> new LiteralExpr(LiteralExpr.Kind.NULL, null, slice(r), null, r);
        };
    }

    private LambdaExpr lowerLambda(LambdaExpression tree) {
        ParamDecl[] params;
        if (tree.arguments == null || tree.arguments.length == 0) {
            params = EmptyArrays.PARAM_DECL;
        } else {
            params = new ParamDecl[tree.arguments.length];
            for (int i = 0; i < tree.arguments.length; i++) {
                params[i] = lowerParam(tree.arguments[i], false);
            }
        }
        ch.castleridge.javals.ast.Expression exprBody = null;
        ch.castleridge.javals.ast.Block blockBody = null;
        if (tree.body instanceof Expression expr) exprBody = lowerExpr(expr);
        else if (tree.body instanceof Block block) blockBody = lowerBlock(block);
        else if (tree.body instanceof Statement stmt) {
            blockBody = new ch.castleridge.javals.ast.Block(
                    new ch.castleridge.javals.ast.Statement[] { lowerStmt(stmt) }, range(tree.body));
        }
        boolean elided = tree.arguments == null || tree.arguments.length == 0 || tree.arguments[0].type == null;
        return new LambdaExpr(params, elided, exprBody, blockBody, jtype(tree.resolvedType), range(tree));
    }

    private MemberRefExpr lowerMemberRef(ReferenceExpression tree) {
        Identifier name = new Identifier(str(tree.selector), range(tree), symbolOf(tree.binding));
        TypeNode type = tree.lhs instanceof TypeReference typeRef ? lowerTypeRef(typeRef) : null;
        ch.castleridge.javals.ast.Expression expr = type == null && tree.lhs != null ? lowerExpr(tree.lhs) : null;
        MemberRefExpr.Mode mode = tree.isConstructorReference() ? MemberRefExpr.Mode.NEW : MemberRefExpr.Mode.INVOKE;
        return new MemberRefExpr(expr, type, mode, name, EmptyArrays.TYPE_NODE, jtype(tree.resolvedType), range(tree));
    }

    private TypeNode lowerTypeRef(TypeReference tree) {
        return lowerTypeRef(tree, null);
    }

    /**
     * @param constructorName when non-null (from {@code new}), bind the simple-name Identifier
     *                        of the allocated type to this constructor symbol on first construction
     */
    private TypeNode lowerTypeRef(TypeReference tree, Symbol constructorName) {
        if (tree == null) return null;
        SourceRange r = range(tree);
        JType resolved = jtype(tree.resolvedType);
        if (tree instanceof Wildcard wild) {
            return switch (wild.kind) {
                case Wildcard.UNBOUND -> new WildcardTypeNode(WildcardTypeNode.BoundKind.UNBOUNDED, null, resolved, r);
                case Wildcard.EXTENDS -> new WildcardTypeNode(WildcardTypeNode.BoundKind.EXTENDS,
                        lowerTypeRef(wild.bound), resolved, r);
                default -> new WildcardTypeNode(WildcardTypeNode.BoundKind.SUPER,
                        lowerTypeRef(wild.bound), resolved, r);
            };
        }
        if (tree instanceof ParameterizedSingleTypeReference param) {
            TypeName raw = typeName(new Identifier[] {
                    new Identifier(str(param.token),
                            new SourceRange(param.sourceStart, param.sourceStart + param.token.length),
                            lastTypeSymbol(validType(param.resolvedType), constructorName))
            }, null, r);
            TypeNode[] args;
            if (param.typeArguments == null || param.typeArguments.length == 0) {
                args = EmptyArrays.TYPE_NODE;
            } else {
                args = new TypeNode[param.typeArguments.length];
                for (int i = 0; i < param.typeArguments.length; i++) {
                    args[i] = lowerTypeRef(param.typeArguments[i]);
                }
            }
            return new ParameterizedTypeNode(raw, args, resolved, r);
        }
        if (tree instanceof ParameterizedQualifiedTypeReference param) {
            TypeName raw = typeName(qualifiedTypeIdents(param.tokens, param.sourcePositions, param.resolvedType,
                    constructorName), null, r);
            TypeNode[] args = EmptyArrays.TYPE_NODE;
            if (param.typeArguments != null && param.typeArguments.length > 0
                    && param.typeArguments[param.typeArguments.length - 1] != null) {
                TypeReference[] lastArgs = param.typeArguments[param.typeArguments.length - 1];
                if (lastArgs.length > 0) {
                    args = new TypeNode[lastArgs.length];
                    for (int i = 0; i < lastArgs.length; i++) {
                        args[i] = lowerTypeRef(lastArgs[i]);
                    }
                }
            }
            return new ParameterizedTypeNode(raw, args, resolved, r);
        }
        if (tree instanceof ArrayTypeReference array) {
            TypeNode element = typeName(new Identifier[] {
                    new Identifier(str(array.token),
                            new SourceRange(array.sourceStart, array.sourceStart + array.token.length),
                            symbolOf(validType(array.resolvedType)))
            }, null, r);
            TypeNode current = element;
            int dims = array.dimensions();
            for (int i = 0; i < dims; i++) {
                boolean outermost = i == dims - 1;
                current = new ArrayTypeNode(current, outermost ? resolved : null, r);
            }
            return current;
        }
        if (tree instanceof ArrayQualifiedTypeReference array) {
            TypeNode element = typeName(qualifiedTypeIdents(array.tokens, array.sourcePositions, array.resolvedType,
                    null), null, r);
            TypeNode current = element;
            int dims = array.dimensions();
            for (int i = 0; i < dims; i++) {
                boolean outermost = i == dims - 1;
                current = new ArrayTypeNode(current, outermost ? resolved : null, r);
            }
            return current;
        }
        if (tree instanceof QualifiedTypeReference qual) {
            return typeName(qualifiedTypeIdents(qual.tokens, qual.sourcePositions, qual.resolvedType,
                    constructorName), resolved, r);
        }
        if (tree instanceof SingleTypeReference single) {
            if (isPrimitive(single.token)) {
                return primitive(single.token, resolved, r);
            }
            return typeName(new Identifier[] {
                    new Identifier(str(single.token), r,
                            lastTypeSymbol(validType(single.resolvedType), constructorName))
            }, resolved, r);
        }
        return typeName(new Identifier[] {
                new Identifier(tree.toString(), r,
                        lastTypeSymbol(validType(tree.resolvedType), constructorName))
        }, resolved, r);
    }

    private TypeName typeName(Identifier[] names, JType resolved, SourceRange range) {
        return new TypeName(names, resolved, range);
    }

    /**
     * Lower each segment of a qualified type name. ECJ only stores the leaf
     * {@code resolvedType} on the type reference (no {@code otherBindings}), so
     * outer type segments such as {@code Field} in {@code Field.Mode} are
     * recovered by walking {@link ReferenceBinding#enclosingType()} from the
     * leaf. Package segments left of the outermost type stay unbound.
     */
    private Identifier[] qualifiedTypeIdents(char[][] tokens, long[] positions, TypeBinding resolved,
                                             Symbol constructorName) {
        if (tokens == null || tokens.length == 0) return EmptyArrays.IDENTIFIER;
        Symbol[] symbols = new Symbol[tokens.length];
        TypeBinding leaf = validType(resolved);
        if (leaf != null) {
            leaf = leaf.leafComponentType();
        }
        ReferenceBinding current = leaf instanceof ReferenceBinding rb && rb.isValidBinding() ? rb : null;
        for (int i = tokens.length - 1; i >= 0 && current != null; i--) {
            symbols[i] = i == tokens.length - 1
                    ? lastTypeSymbol(current, constructorName)
                    : symbolOf(current);
            current = current.enclosingType();
        }
        Identifier[] out = new Identifier[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            SourceRange nr = positions == null || i >= positions.length
                    ? SourceRange.NONE : posRange(positions[i]);
            out[i] = new Identifier(new String(tokens[i]), nr, symbols[i]);
        }
        return out;
    }

    private Symbol lastTypeSymbol(Binding typeBinding, Symbol constructorName) {
        return constructorName != null ? constructorName : symbolOf(typeBinding);
    }

    private ch.castleridge.javals.ast.Annotation[] annos(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) return EmptyArrays.ANNOTATION;
        ch.castleridge.javals.ast.Annotation[] out = new ch.castleridge.javals.ast.Annotation[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            Annotation annotation = annotations[i];
            TypeName name = annotation.type == null ? typeName(EmptyArrays.IDENTIFIER, null, SourceRange.NONE)
                    : (lowerTypeRef(annotation.type) instanceof TypeName tn ? tn
                    : typeName(EmptyArrays.IDENTIFIER, null, range(annotation.type)));
            AnnoArg[] args;
            if (annotation instanceof MarkerAnnotation) {
                args = EmptyArrays.ANNO_ARG;
            } else if (annotation instanceof SingleMemberAnnotation single) {
                args = new AnnoArg[] {
                        new AnnoArg(null, lowerExpr(single.memberValue), range(single.memberValue))
                };
            } else if (annotation instanceof NormalAnnotation normal
                    && normal.memberValuePairs != null && normal.memberValuePairs.length > 0) {
                args = new AnnoArg[normal.memberValuePairs.length];
                for (int j = 0; j < normal.memberValuePairs.length; j++) {
                    var pair = normal.memberValuePairs[j];
                    args[j] = new AnnoArg(new Identifier(str(pair.name), SourceRange.NONE, null),
                            lowerExpr(pair.value), range(pair));
                }
            } else {
                args = EmptyArrays.ANNO_ARG;
            }
            out[i] = new ch.castleridge.javals.ast.Annotation(name, args, jtype(annotation.resolvedType),
                    range(annotation));
        }
        return out;
    }

    private TypeNode[] lowerTypeRefs(TypeReference[] refs) {
        if (refs == null || refs.length == 0) return EmptyArrays.TYPE_NODE;
        TypeNode[] out = new TypeNode[refs.length];
        for (int i = 0; i < refs.length; i++) {
            out[i] = lowerTypeRef(refs[i]);
        }
        return out;
    }

    private ch.castleridge.javals.ast.Expression[] mapExprs(Expression[] exprs) {
        if (exprs == null || exprs.length == 0) return EmptyArrays.EXPRESSION;
        ch.castleridge.javals.ast.Expression[] out = new ch.castleridge.javals.ast.Expression[exprs.length];
        for (int i = 0; i < exprs.length; i++) {
            Expression expr = exprs[i];
            ch.castleridge.javals.ast.Expression lowered = expr == null ? null : lowerExpr(expr);
            out[i] = lowered == null
                    ? new ErroneousExpr(EmptyArrays.NODE, null, SourceRange.NONE)
                    : lowered;
        }
        return out;
    }

    private Symbol symbolOf(Binding binding) {
        if (binding == null || !binding.isValidBinding()) return null;
        Symbol existing = interned.get(binding);
        if (existing != null) return existing;
        if (binding instanceof TypeBinding type) return typeSymbol(type);
        if (binding instanceof MethodBinding method) return methodSymbol(method, null);
        if (binding instanceof FieldBinding field) {
            return (field.modifiers & ClassFileConstants.AccEnum) != 0 ? enumSymbol(field, null) : fieldSymbol(field, null);
        }
        if (binding instanceof LocalVariableBinding local) return localSymbol(local);
        if (binding instanceof PackageBinding pkg) {
            PackageSymbol symbol = new PackageSymbol(new String(pkg.readableName()),
                    SymbolKey.local(new String(pkg.readableName())));
            interned.put(binding, symbol);
            return symbol;
        }
        if (binding instanceof TypeVariableBinding tv) return typeVarSymbol(tv);
        return null;
    }

    private TypeSymbol typeSymbol(TypeBinding binding) {
        TypeBinding leaf = binding == null ? null : binding.leafComponentType();
        if (!(leaf instanceof ReferenceBinding ref) || !ref.isValidBinding()) return null;
        Symbol existing = interned.get(ref);
        if (existing instanceof TypeSymbol found) return found;
        String jvm = new String(ref.erasure().constantPoolName());
        TypeSymbol cached = typesByJvm.get(jvm);
        if (cached != null) {
            interned.put(ref, cached);
            return cached;
        }
        TypeDeclKind kind = TypeDeclKind.CLASS;
        if (ref.isAnnotationType()) kind = TypeDeclKind.ANNOTATION;
        else if (ref.isInterface()) kind = TypeDeclKind.INTERFACE;
        else if (ref.isEnum()) kind = TypeDeclKind.ENUM;
        else if (ref.isRecord()) kind = TypeDeclKind.RECORD;
        String binary = new String(ref.erasure().constantPoolName()).replace('/', '.');
        TypeSymbol symbol = new TypeSymbol(kind, binary, astKey(ref), EmptyArrays.TYPE_VAR_SYMBOL);
        interned.put(ref, symbol);
        typesByJvm.put(jvm, symbol);
        return symbol;
    }

    private MethodSymbol methodSymbol(MethodBinding binding, TypeSymbol owner) {
        if (binding == null || !binding.isValidBinding()) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof MethodSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(binding.declaringClass);
        JType[] params;
        if (binding.parameters == null || binding.parameters.length == 0) {
            params = EmptyArrays.JTYPE;
        } else {
            params = new JType[binding.parameters.length];
            for (int i = 0; i < binding.parameters.length; i++) {
                params[i] = jtype(binding.parameters[i]);
            }
        }
        MethodSymbol symbol = new MethodSymbol(new String(binding.selector), jtype(binding.returnType),
                params, resolvedOwner, astKey(binding), binding.isConstructor(), binding.isSynthetic());
        interned.put(binding, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private FieldSymbol fieldSymbol(FieldBinding binding, TypeSymbol owner) {
        if (binding == null || !binding.isValidBinding()) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof FieldSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(binding.declaringClass);
        FieldSymbol symbol = new FieldSymbol(new String(binding.name), jtype(binding.type),
                resolvedOwner, astKey(binding), binding.isSynthetic());
        interned.put(binding, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private EnumConstantSymbol enumSymbol(FieldBinding binding, TypeSymbol owner) {
        if (binding == null || !binding.isValidBinding()) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof EnumConstantSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(binding.declaringClass);
        EnumConstantSymbol symbol = new EnumConstantSymbol(new String(binding.name), resolvedOwner, astKey(binding));
        interned.put(binding, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private RecordComponentSymbol recordComponentSymbol(
            org.eclipse.jdt.internal.compiler.lookup.RecordComponentBinding binding, TypeSymbol owner) {
        if (binding == null) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof RecordComponentSymbol found) return found;
        RecordComponentSymbol symbol = new RecordComponentSymbol(new String(binding.name), jtype(binding.type),
                owner, astKey(binding), null);
        interned.put(binding, symbol);
        if (owner != null) owner.addMember(symbol);
        return symbol;
    }

    private LocalSymbol localSymbol(LocalVariableBinding binding) {
        if (binding == null) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof LocalSymbol found) return found;
        LocalSymbol symbol = new LocalSymbol(new String(binding.name), jtype(binding.type));
        interned.put(binding, symbol);
        return symbol;
    }

    private TypeVarSymbol typeVarSymbol(TypeVariableBinding binding) {
        if (binding == null) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof TypeVarSymbol found) return found;
        TypeVarSymbol symbol = new TypeVarSymbol(new String(binding.sourceName), EmptyArrays.JTYPE);
        interned.put(binding, symbol);
        return symbol;
    }

    private SymbolKey astKey(Binding binding) {
        if (binding instanceof LocalVariableBinding local) {
            return SymbolKey.local(new String(local.name));
        }
        if (binding instanceof TypeVariableBinding tv) {
            return SymbolKey.local(new String(tv.sourceName));
        }
        if (binding instanceof TypeBinding type) {
            TypeBinding leaf = type.leafComponentType().erasure();
            if (leaf instanceof ReferenceBinding reference) {
                String ownerJvm = new String(reference.constantPoolName());
                Optional<String> origin = origin(ownerJvm);
                if (origin.isPresent()) {
                    return SymbolKey.of("T:" + origin.get() + "|" + ownerJvm.replace('/', '.'),
                            new String(reference.sourceName()), origin.get());
                }
            }
        } else if (binding instanceof MethodBinding method && method.declaringClass != null) {
            String ownerJvm = new String(method.declaringClass.erasure().constantPoolName());
            Optional<String> origin = origin(ownerJvm);
            if (origin.isPresent()) {
                String name = new String(method.selector);
                String simple = method.isConstructor() ? new String(method.declaringClass.sourceName()) : name;
                StringBuilder sb = new StringBuilder(64);
                sb.append("M:").append(origin.get()).append('|')
                        .append(ownerJvm.replace('/', '.')).append('#').append(name).append('(');
                if (method.parameters != null) {
                    for (int i = 0; i < method.parameters.length; i++) {
                        if (i > 0) sb.append(',');
                        appendErasureJvmName(sb, method.parameters[i]);
                    }
                }
                sb.append(')');
                return SymbolKey.of(sb.toString(), simple, origin.get());
            }
        } else if (binding instanceof FieldBinding field && field.declaringClass != null) {
            String ownerJvm = new String(field.declaringClass.erasure().constantPoolName());
            Optional<String> origin = origin(ownerJvm);
            if (origin.isPresent()) {
                String name = new String(field.name);
                return SymbolKey.of("F:" + origin.get() + "|" + ownerJvm.replace('/', '.') + "#" + name,
                        name, origin.get());
            }
        } else if (binding instanceof org.eclipse.jdt.internal.compiler.lookup.RecordComponentBinding component
                && component.declaringRecord != null) {
            String ownerJvm = new String(component.declaringRecord.erasure().constantPoolName());
            Optional<String> origin = origin(ownerJvm);
            if (origin.isPresent()) {
                String name = new String(component.name);
                return SymbolKey.of("F:" + origin.get() + "|" + ownerJvm.replace('/', '.') + "#" + name,
                        name, origin.get());
            }
        }
        String name = binding == null ? "" : new String(binding.readableName());
        return SymbolKey.local(name);
    }

    /** Append erasure display name (same form as javac {@code Types.erasure(...).toString()}). */
    private static void appendErasureJvmName(StringBuilder sb, TypeBinding type) {
        if (type == null) {
            sb.append('?');
            return;
        }
        char[] name = type.erasure().readableName();
        if (name == null || name.length == 0) {
            sb.append('?');
        } else {
            sb.append(name);
        }
    }

    private Optional<String> origin(String ownerJvm) {
        Optional<String> cached = originByJvm.get(ownerJvm);
        if (cached != null) return cached;
        Optional<String> computed = computeOrigin(ownerJvm);
        originByJvm.put(ownerJvm, computed);
        return computed;
    }

    private Optional<String> computeOrigin(String ownerJvm) {
        TypeEntry entry = indexedEntry(ownerJvm);
        String indexed = indexedOrigin(entry);
        boolean declaredHere = declares(ownerJvm);
        if (indexed != null && (!declaredHere || sameDeclaration(indexed, entry))) {
            return Optional.of(indexed);
        }
        if (declaredHere) return Optional.of(uri);
        return Optional.empty();
    }

    /**
     * The buffer is the indexed declaration when it is that resource, or the
     * attached {@code .java} companion of an indexed {@code .class}. A
     * workspace file that only shadows the binary name keeps its own URI.
     */
    private boolean sameDeclaration(String indexed, TypeEntry entry) {
        if (FileUris.sameFile(uri, indexed)) return true;
        if (entry == null) return false;
        return AttachedSource.javaUri(indexed, entry.sourceUri(), sourceJarByBinaryJar)
                .filter(javaUri -> FileUris.sameFile(uri, javaUri))
                .isPresent();
    }

    private TypeEntry indexedEntry(String ownerJvm) {
        if (index == null || ownerJvm == null) return null;
        return classpath.pick(index.getAll(ownerJvm), TypeEntry::sourceUri);
    }

    private static String indexedOrigin(TypeEntry entry) {
        if (entry == null) return null;
        String resourceUri = entry.resourceUri();
        if (resourceUri == null || resourceUri.isBlank()) return null;
        return resourceUri;
    }

    private boolean declares(String ownerJvm) {
        if (ownerJvm == null || unit.types == null) return false;
        Set<String> names = declaredJvmNames;
        if (names == null) {
            names = new HashSet<>();
            collectDeclaredJvmNames(unit.types, names);
            declaredJvmNames = names;
        }
        return names.contains(ownerJvm);
    }

    private static void collectDeclaredJvmNames(TypeDeclaration[] types, Set<String> out) {
        if (types == null) return;
        for (TypeDeclaration type : types) {
            if (type.binding != null && type.binding.constantPoolName() != null) {
                out.add(new String(type.binding.constantPoolName()));
            }
            collectDeclaredJvmNames(type.memberTypes, out);
        }
    }

    private JType jtype(TypeBinding binding) {
        if (binding == null || !binding.isValidBinding()) return JType.ERROR;
        // isBaseType covers primitives, void, and null; isPrimitiveType excludes void/null.
        if (binding.isBaseType()) {
            return switch (binding.id) {
                case TypeIds.T_boolean -> JType.Primitive.BOOLEAN;
                case TypeIds.T_byte -> JType.Primitive.BYTE;
                case TypeIds.T_short -> JType.Primitive.SHORT;
                case TypeIds.T_char -> JType.Primitive.CHAR;
                case TypeIds.T_int -> JType.Primitive.INT;
                case TypeIds.T_long -> JType.Primitive.LONG;
                case TypeIds.T_float -> JType.Primitive.FLOAT;
                case TypeIds.T_double -> JType.Primitive.DOUBLE;
                case TypeIds.T_void -> JType.VOID;
                case TypeIds.T_null -> JType.NULL;
                default -> JType.ERROR;
            };
        }
        if (binding.isArrayType()) return JType.array(jtype(binding.leafComponentType()));
        if (binding.isTypeVariable()) return new JType.TypeVar(new String(binding.sourceName()));
        if (binding instanceof ReferenceBinding ref) {
            char[] name = ref.erasure().constantPoolName();
            if (name == null) return JType.ERROR;
            return declaredType(new String(name));
        }
        return JType.ERROR;
    }

    private JType.Declared declaredType(String jvmName) {
        JType.Declared cached = declaredByJvm.get(jvmName);
        if (cached != null) return cached;
        JType.Declared created = JType.Declared.of(jvmName);
        declaredByJvm.put(jvmName, created);
        return created;
    }

    private TypeBinding validType(TypeBinding binding) {
        return binding != null && binding.isValidBinding() ? binding : null;
    }

    private TypeDeclKind typeKind(TypeDeclaration tree) {
        int kind = TypeDeclaration.kind(tree.modifiers);
        if (kind == TypeDeclaration.INTERFACE_DECL) return TypeDeclKind.INTERFACE;
        if (kind == TypeDeclaration.ENUM_DECL) return TypeDeclKind.ENUM;
        if (kind == TypeDeclaration.ANNOTATION_TYPE_DECL) return TypeDeclKind.ANNOTATION;
        if (HAS_RECORD_DECL) {
            if (isRecordDeclKind(kind)) return TypeDeclKind.RECORD;
        } else if (tree.binding != null && tree.binding.isRecord()) {
            return TypeDeclKind.RECORD;
        }
        return TypeDeclKind.CLASS;
    }

    /** Isolated so {@code RECORD_DECL} is only resolved when {@link #HAS_RECORD_DECL} is true. */
    private static boolean isRecordDeclKind(int kind) {
        return kind == TypeDeclaration.RECORD_DECL;
    }

    private static boolean detectRecordDecl() {
        try {
            @SuppressWarnings("unused")
            int ignored = TypeDeclaration.RECORD_DECL;
            return true;
        } catch (NoSuchFieldError e) {
            return false;
        }
    }

    private static int mods(int bits) {
        int out = 0;
        if ((bits & ClassFileConstants.AccPublic) != 0) out |= Modifier.PUBLIC.bit();
        if ((bits & ClassFileConstants.AccProtected) != 0) out |= Modifier.PROTECTED.bit();
        if ((bits & ClassFileConstants.AccPrivate) != 0) out |= Modifier.PRIVATE.bit();
        if ((bits & ClassFileConstants.AccAbstract) != 0) out |= Modifier.ABSTRACT.bit();
        if ((bits & ClassFileConstants.AccStatic) != 0) out |= Modifier.STATIC.bit();
        if ((bits & ClassFileConstants.AccFinal) != 0) out |= Modifier.FINAL.bit();
        if ((bits & ClassFileConstants.AccNative) != 0) out |= Modifier.NATIVE.bit();
        if ((bits & ClassFileConstants.AccSynchronized) != 0) out |= Modifier.SYNCHRONIZED.bit();
        if ((bits & ClassFileConstants.AccTransient) != 0) out |= Modifier.TRANSIENT.bit();
        if ((bits & ClassFileConstants.AccVolatile) != 0) out |= Modifier.VOLATILE.bit();
        if ((bits & ClassFileConstants.AccStrictfp) != 0) out |= Modifier.STRICTFP.bit();
        if ((bits & ExtraCompilerModifiers.AccDefaultMethod) != 0) out |= Modifier.DEFAULT.bit();
        if ((bits & ExtraCompilerModifiers.AccSealed) != 0) out |= Modifier.SEALED.bit();
        if ((bits & ExtraCompilerModifiers.AccNonSealed) != 0) out |= Modifier.NON_SEALED.bit();
        return out;
    }

    private SourceRange range(ASTNode node) {
        if (node == null) return SourceRange.NONE;
        return new SourceRange(Math.max(0, node.sourceStart), inclusiveEnd(node.sourceEnd));
    }

    private int inclusiveEnd(int sourceEnd) {
        return Math.min(source.length(), Math.max(0, sourceEnd) + 1);
    }

    private SourceRange nameRange(char[] name, int start, int end) {
        if (name == null || name.length == 0) {
            return new SourceRange(Math.max(0, start), inclusiveEnd(end));
        }
        int from = Math.max(0, start);
        int to = Math.min(source.length(), Math.max(from, end + 1));
        // Fast path: ECJ sourceStart..sourceEnd already names the selector
        if (to - from == name.length && regionMatches(source, from, name)) {
            return new SourceRange(from, to);
        }
        int found = indexOfChars(source, name, from, to);
        if (found < 0) {
            return new SourceRange(from, Math.min(source.length(), from + name.length));
        }
        return new SourceRange(found, found + name.length);
    }

    private static boolean regionMatches(String source, int from, char[] name) {
        if (from + name.length > source.length()) return false;
        for (int i = 0; i < name.length; i++) {
            if (source.charAt(from + i) != name[i]) return false;
        }
        return true;
    }

    /** Find {@code name} in {@code source} within [{@code from}, {@code to}). */
    private static int indexOfChars(String source, char[] name, int from, int to) {
        int limit = to - name.length;
        outer:
        for (int i = from; i <= limit; i++) {
            for (int j = 0; j < name.length; j++) {
                if (source.charAt(i + j) != name[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private SourceRange namePos(long packed) {
        return posRange(packed);
    }

    private SourceRange posRange(long packed) {
        int start = (int) (packed >>> 32);
        int end = (int) packed;
        return new SourceRange(Math.max(0, start), inclusiveEnd(end));
    }

    private static long pack(int start, int end) {
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    private SourceRange span(SourceRange a, SourceRange b) {
        if (a == null || !a.isPresent()) return b;
        if (b == null || !b.isPresent()) return a;
        return new SourceRange(Math.min(a.start(), b.start()), Math.max(a.end(), b.end()));
    }

    private String slice(SourceRange range) {
        if (!range.isPresent()) return "";
        int lo = Math.max(0, range.start());
        int hi = Math.min(source.length(), range.end());
        return lo >= hi ? "" : source.substring(lo, hi);
    }

    private static String str(char[] chars) {
        return chars == null ? "" : new String(chars);
    }

    private static boolean isPrimitive(char[] token) {
        String n = str(token);
        return switch (n) {
            case "boolean", "byte", "short", "int", "long", "char", "float", "double", "void" -> true;
            default -> false;
        };
    }

    private TypeNode primitive(char[] token, JType resolved, SourceRange range) {
        String n = str(token);
        if ("void".equals(n)) return new VoidTypeNode(resolved, range);
        JType.Primitive kind = switch (n) {
            case "boolean" -> JType.Primitive.BOOLEAN;
            case "byte" -> JType.Primitive.BYTE;
            case "short" -> JType.Primitive.SHORT;
            case "long" -> JType.Primitive.LONG;
            case "char" -> JType.Primitive.CHAR;
            case "float" -> JType.Primitive.FLOAT;
            case "double" -> JType.Primitive.DOUBLE;
            default -> JType.Primitive.INT;
        };
        return new PrimitiveTypeNode(kind, resolved, range);
    }

    private BinaryExpr.Op binaryOp(int op) {
        return switch (op) {
            case OperatorIds.PLUS -> BinaryExpr.Op.PLUS;
            case OperatorIds.MINUS -> BinaryExpr.Op.MINUS;
            case OperatorIds.MULTIPLY -> BinaryExpr.Op.MULTIPLY;
            case OperatorIds.DIVIDE -> BinaryExpr.Op.DIVIDE;
            case OperatorIds.REMAINDER -> BinaryExpr.Op.REMAINDER;
            case OperatorIds.AND -> BinaryExpr.Op.AND;
            case OperatorIds.OR -> BinaryExpr.Op.OR;
            case OperatorIds.XOR -> BinaryExpr.Op.XOR;
            case OperatorIds.LEFT_SHIFT -> BinaryExpr.Op.LEFT_SHIFT;
            case OperatorIds.RIGHT_SHIFT -> BinaryExpr.Op.RIGHT_SHIFT;
            case OperatorIds.UNSIGNED_RIGHT_SHIFT -> BinaryExpr.Op.UNSIGNED_RIGHT_SHIFT;
            case OperatorIds.LESS -> BinaryExpr.Op.LESS;
            case OperatorIds.LESS_EQUAL -> BinaryExpr.Op.LESS_EQUAL;
            case OperatorIds.GREATER -> BinaryExpr.Op.GREATER;
            case OperatorIds.GREATER_EQUAL -> BinaryExpr.Op.GREATER_EQUAL;
            case OperatorIds.EQUAL_EQUAL -> BinaryExpr.Op.EQUAL;
            case OperatorIds.NOT_EQUAL -> BinaryExpr.Op.NOT_EQUAL;
            case OperatorIds.AND_AND -> BinaryExpr.Op.CONDITIONAL_AND;
            case OperatorIds.OR_OR -> BinaryExpr.Op.CONDITIONAL_OR;
            default -> BinaryExpr.Op.PLUS;
        };
    }

    private BinaryExpr.Op equalOp(EqualExpression tree) {
        int op = (tree.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
        return op == OperatorIds.NOT_EQUAL ? BinaryExpr.Op.NOT_EQUAL : BinaryExpr.Op.EQUAL;
    }

    private UnaryExpr.Op unaryOp(int op, boolean postfix) {
        if (postfix) {
            return op == OperatorIds.MINUS ? UnaryExpr.Op.POST_DECREMENT : UnaryExpr.Op.POST_INCREMENT;
        }
        return switch (op) {
            case OperatorIds.PLUS -> UnaryExpr.Op.PLUS;
            case OperatorIds.MINUS -> UnaryExpr.Op.MINUS;
            case OperatorIds.NOT -> UnaryExpr.Op.NOT;
            case OperatorIds.TWIDDLE -> UnaryExpr.Op.COMPLEMENT;
            case OperatorIds.PLUS_PLUS -> UnaryExpr.Op.PRE_INCREMENT;
            case OperatorIds.MINUS_MINUS -> UnaryExpr.Op.PRE_DECREMENT;
            default -> UnaryExpr.Op.PLUS;
        };
    }

    private AssignExpr.Op assignOp(int op) {
        return switch (op) {
            case OperatorIds.PLUS -> AssignExpr.Op.PLUS_ASSIGN;
            case OperatorIds.MINUS -> AssignExpr.Op.MINUS_ASSIGN;
            case OperatorIds.MULTIPLY -> AssignExpr.Op.MULTIPLY_ASSIGN;
            case OperatorIds.DIVIDE -> AssignExpr.Op.DIVIDE_ASSIGN;
            case OperatorIds.REMAINDER -> AssignExpr.Op.REMAINDER_ASSIGN;
            case OperatorIds.AND -> AssignExpr.Op.AND_ASSIGN;
            case OperatorIds.OR -> AssignExpr.Op.OR_ASSIGN;
            case OperatorIds.XOR -> AssignExpr.Op.XOR_ASSIGN;
            case OperatorIds.LEFT_SHIFT -> AssignExpr.Op.LEFT_SHIFT_ASSIGN;
            case OperatorIds.RIGHT_SHIFT -> AssignExpr.Op.RIGHT_SHIFT_ASSIGN;
            case OperatorIds.UNSIGNED_RIGHT_SHIFT -> AssignExpr.Op.UNSIGNED_RIGHT_SHIFT_ASSIGN;
            default -> AssignExpr.Op.ASSIGN;
        };
    }

    /** Placeholder so TypeIds is in scope for constant folding. */
    private static final class TypeIds {
        static final int T_boolean = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_boolean;
        static final int T_byte = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_byte;
        static final int T_short = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_short;
        static final int T_char = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_char;
        static final int T_int = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_int;
        static final int T_long = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_long;
        static final int T_float = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_float;
        static final int T_double = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_double;
        static final int T_void = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_void;
        static final int T_null = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_null;
        static final int T_JavaLangString = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_JavaLangString;
    }

}
