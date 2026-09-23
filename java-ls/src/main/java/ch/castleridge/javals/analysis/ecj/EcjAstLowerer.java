/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;

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
import ch.castleridge.javals.ast.TypeParamDecl;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.TypeNode;
import ch.castleridge.javals.ast.TypeVarSymbol;
import ch.castleridge.javals.ast.UnaryExpr;
import ch.castleridge.javals.ast.VarFragment;
import ch.castleridge.javals.ast.VarTypeNode;
import ch.castleridge.javals.ast.VoidTypeNode;
import ch.castleridge.javals.ast.WhileStmt;
import ch.castleridge.javals.ast.WildcardTypeNode;
import ch.castleridge.javals.ast.YieldStmt;
import ch.castleridge.javals.analysis.FileUris;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.TypeEntry;

final class EcjAstLowerer {

    private final CompilationUnitDeclaration unit;
    private final String uri;
    private final String source;
    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<Binding, Symbol> interned = new IdentityHashMap<>();
    private final Map<String, TypeSymbol> typesByJvm = new HashMap<>();

    private EcjAstLowerer(CompilationUnitDeclaration unit,
                          String uri,
                          String source,
                          Index index,
                          ClasspathOrder classpath) {
        this.unit = unit;
        this.uri = uri == null ? "" : uri;
        this.source = source == null ? "" : source;
        this.index = index;
        this.classpath = classpath == null ? ClasspathOrder.UNRESTRICTED : classpath;
    }

    static CompilationUnit lower(CompilationUnitDeclaration unit,
                                 URI uri,
                                 String source,
                                 Index index,
                                 ClasspathOrder classpath) {
        String text = source == null ? "" : source;
        String u = uri == null ? "" : uri.toString();
        if (unit == null) {
            return new CompilationUnit(new SourceFile(u, text), null, List.of(), List.of(), null,
                    new SourceRange(0, text.length()));
        }
        return new EcjAstLowerer(unit, u, text, index, classpath).lowerUnit();
    }

    static CompilationUnit diet(CompilationUnitDeclaration unit, String uri, String source) {
        return new EcjAstLowerer(unit, uri == null ? "" : uri, source, null, ClasspathOrder.UNRESTRICTED).lowerUnit();
    }

    private CompilationUnit lowerUnit() {
        PackageDecl pkg = unit.currentPackage == null ? null
                : new PackageDecl(List.of(), importNames(unit.currentPackage), range(unit.currentPackage));
        List<ImportDecl> imports = new ArrayList<>();
        if (unit.imports != null) {
            for (ImportReference imp : unit.imports) imports.add(lowerImport(imp));
        }
        List<TypeDecl> types = new ArrayList<>();
        if (unit.types != null) {
            for (TypeDeclaration type : unit.types) {
                if (type != null) types.add(lowerType(type));
            }
        }
        return new CompilationUnit(new SourceFile(uri, source), pkg, imports, types, null,
                new SourceRange(0, source.length()));
    }

    private ImportDecl lowerImport(ImportReference tree) {
        List<Identifier> names = importNames(tree);
        boolean onDemand = (tree.bits & ASTNode.OnDemand) != 0;
        return new ImportDecl(tree.isStatic(), onDemand, names, range(tree));
    }

    private List<Identifier> importNames(ImportReference tree) {
        if (tree.tokens == null) return List.of();
        List<Identifier> out = new ArrayList<>();
        for (int i = 0; i < tree.tokens.length; i++) {
            long pos = tree.sourcePositions == null || i >= tree.sourcePositions.length
                    ? pack(tree.sourceStart, tree.sourceEnd) : tree.sourcePositions[i];
            out.add(new Identifier(new String(tree.tokens[i]), posRange(pos), null));
        }
        bindImportNames(out, tree.isStatic(), (tree.bits & ASTNode.OnDemand) != 0);
        return out;
    }

    private void bindImportNames(List<Identifier> names, boolean staticImport, boolean onDemand) {
        if (names.isEmpty()) return;
        String jvm = "";
        TypeSymbol type = null;
        for (int i = 0; i < names.size(); i++) {
            Identifier ident = names.get(i);
            boolean last = i == names.size() - 1;
            String slash = jvm.isEmpty() ? ident.name() : jvm + "/" + ident.name();
            String nested = jvm.isEmpty() ? ident.name() : jvm + "$" + ident.name();
            TypeSymbol found = typeSymbolForJvm(nested);
            if (found == null) found = typeSymbolForJvm(slash);
            if (found != null) {
                ident.setSymbol(found);
                type = found;
                jvm = found.jvmBinaryName().replace('.', '/');
                continue;
            }
            if (type != null && last && staticImport && !onDemand) {
                continue;
            }
            String qualified = jvm.isEmpty() ? ident.name() : jvm.replace('/', '.') + "." + ident.name();
            ident.setSymbol(new PackageSymbol(qualified, SymbolKey.local(ident.name())));
            jvm = slash;
        }
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
                List.of());
        typesByJvm.put(jvm, symbol);
        return symbol;
    }

    private TypeDecl lowerType(TypeDeclaration tree) {
        TypeDeclKind kind = typeKind(tree);
        TypeSymbol symbol = typeSymbol(tree.binding);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        List<TypeParamDecl> typeParams = lowerTypeParams(tree.typeParameters);
        TypeNode superclass = tree.superclass == null ? null : lowerTypeRef(tree.superclass);
        List<TypeNode> interfaces = lowerTypeRefs(tree.superInterfaces);
        List<TypeNode> permits = lowerTypeRefs(tree.permittedTypes);
        List<RecordComponentDecl> components = new ArrayList<>();
        if (tree.recordComponents != null) {
            for (var component : tree.recordComponents) {
                components.add(lowerRecordComponent(component, symbol));
            }
        }
        List<Declaration> members = new ArrayList<>();
        if (tree.memberTypes != null) {
            for (TypeDeclaration nested : tree.memberTypes) members.add(lowerType(nested));
        }
        if (tree.fields != null) {
            for (FieldDeclaration field : tree.fields) {
                Declaration decl = lowerField(field, symbol, kind);
                if (decl != null) members.add(decl);
            }
        }
        if (tree.methods != null) {
            for (AbstractMethodDeclaration method : tree.methods) {
                Declaration decl = lowerMethod(method, symbol, kind);
                if (decl != null) members.add(decl);
            }
        }
        return new TypeDecl(kind, mods(tree.modifiers), annos(tree.annotations), name, typeParams,
                superclass, interfaces, permits, components, members, symbol, range(tree));
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
            List<ch.castleridge.javals.ast.Expression> args = List.of();
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
                List.of(fragment), range(tree));
    }

    private Declaration lowerMethod(AbstractMethodDeclaration tree, TypeSymbol owner, TypeDeclKind ownerKind) {
        if (tree instanceof Clinit) return null;
        MethodSymbol symbol = methodSymbol(tree.binding, owner);
        boolean constructor = tree.isConstructor();
        String raw = str(tree.selector);
        String display = constructor && owner != null ? owner.name() : raw;
        Identifier name = new Identifier(display, nameRange(tree.selector, tree.sourceStart, tree.sourceEnd), symbol);
        List<TypeParamDecl> typeParams = lowerTypeParams(tree.typeParameters());
        List<ParamDecl> params = new ArrayList<>();
        if (tree.arguments != null) {
            for (int i = 0; i < tree.arguments.length; i++) {
                params.add(lowerParam(tree.arguments[i],
                        i == tree.arguments.length - 1 && tree.binding != null && tree.binding.isVarargs()));
            }
        }
        List<TypeNode> thrown = lowerTypeRefs(tree.thrownExceptions);
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
                ? lowerTypeRef(method.returnType) : new VoidTypeNode(SourceRange.NONE);
        return new MethodDecl(mods(tree.modifiers), annos(tree.annotations), typeParams, returnType, name,
                null, params, thrown, body, symbol, range(tree));
    }

    private ParamDecl lowerParam(Argument tree, boolean varargs) {
        LocalSymbol symbol = localSymbol(tree.binding);
        Identifier name = new Identifier(str(tree.name), nameRange(tree.name, tree.sourceStart, tree.sourceEnd), symbol);
        return new ParamDecl(mods(tree.modifiers), annos(tree.annotations), lowerTypeRef(tree.type),
                name, varargs, symbol, range(tree));
    }

    private List<TypeParamDecl> lowerTypeParams(TypeParameter[] params) {
        if (params == null) return List.of();
        List<TypeParamDecl> out = new ArrayList<>();
        for (TypeParameter param : params) {
            TypeVarSymbol symbol = typeVarSymbol(param.binding);
            Identifier name = new Identifier(str(param.name), nameRange(param.name, param.sourceStart, param.sourceEnd), symbol);
            out.add(new TypeParamDecl(List.of(), name, lowerTypeRefs(param.bounds), symbol, range(param)));
        }
        return out;
    }

    private ch.castleridge.javals.ast.Block statementsBlock(AbstractMethodDeclaration tree) {
        List<ch.castleridge.javals.ast.Statement> stmts = new ArrayList<>();
        if (tree instanceof ConstructorDeclaration ctor && ctor.constructorCall != null) {
            stmts.add(lowerConstructorCall(ctor.constructorCall));
        }
        if (tree.statements != null) {
            for (Statement stmt : tree.statements) stmts.add(lowerStmt(stmt));
        }
        return new ch.castleridge.javals.ast.Block(stmts, range(tree));
    }

    private ch.castleridge.javals.ast.Block lowerBlock(Block tree) {
        if (tree == null) return new ch.castleridge.javals.ast.Block(List.of(), SourceRange.NONE);
        List<ch.castleridge.javals.ast.Statement> stmts = new ArrayList<>();
        if (tree.statements != null) {
            for (Statement stmt : tree.statements) stmts.add(lowerStmt(stmt));
        }
        return new ch.castleridge.javals.ast.Block(stmts, range(tree));
    }

    private ch.castleridge.javals.ast.Statement lowerStmt(Statement tree) {
        if (tree == null) return new EmptyStmt(SourceRange.NONE);
        SourceRange r = range(tree);
        if (tree instanceof Block block) return lowerBlock(block);
        if (tree instanceof EmptyStatement) return new EmptyStmt(r);
        if (tree instanceof LocalDeclaration local) return lowerLocal(local);
        if (tree instanceof TypeDeclaration nested) return new LocalTypeStmt(lowerType(nested), r);
        if (tree instanceof IfStatement iff) {
            return new IfStmt(lowerExpr(iff.condition), lowerStmt(iff.thenStatement),
                    iff.elseStatement == null ? null : lowerStmt(iff.elseStatement), r);
        }
        if (tree instanceof WhileStatement loop) {
            return new WhileStmt(lowerExpr(loop.condition), lowerStmt(loop.action), r);
        }
        if (tree instanceof DoStatement loop) {
            return new DoWhileStmt(lowerStmt(loop.action), lowerExpr(loop.condition), r);
        }
        if (tree instanceof ForStatement loop) return lowerFor(loop);
        if (tree instanceof ForeachStatement loop) return lowerForeach(loop);
        if (tree instanceof TryStatement tryStmt) return lowerTry(tryStmt);
        if (tree instanceof SynchronizedStatement sync) {
            return new SynchronizedStmt(lowerExpr(sync.expression), lowerBlock(sync.block), r);
        }
        if (tree instanceof ReturnStatement ret) {
            return new ReturnStmt(ret.expression == null ? null : lowerExpr(ret.expression), r);
        }
        if (tree instanceof ThrowStatement thr) return new ThrowStmt(lowerExpr(thr.exception), r);
        if (tree instanceof BreakStatement brk) {
            return new BreakStmt(brk.label == null ? null : new Identifier(str(brk.label), SourceRange.NONE, null), r);
        }
        if (tree instanceof ContinueStatement cont) {
            return new ContinueStmt(cont.label == null ? null : new Identifier(str(cont.label), SourceRange.NONE, null), r);
        }
        if (tree instanceof YieldStatement yield) return new YieldStmt(lowerExpr(yield.expression), r);
        if (tree instanceof AssertStatement asrt) {
            return new AssertStmt(lowerExpr(asrt.assertExpression),
                    asrt.exceptionArgument == null ? null : lowerExpr(asrt.exceptionArgument), r);
        }
        if (tree instanceof LabeledStatement labeled) {
            return new LabeledStmt(new Identifier(str(labeled.label), SourceRange.NONE, null),
                    lowerStmt(labeled.statement), r);
        }
        if (tree instanceof SwitchStatement sw) {
            return new SwitchStmt(lowerExpr(sw.expression), lowerSwitchArms(sw.statements), r);
        }
        if (tree instanceof ExplicitConstructorCall call) return lowerConstructorCall(call);
        if (tree instanceof Expression expr) return new ExprStmt(lowerExpr(expr), r);
        return new ErroneousStmt(List.of(), r);
    }

    private ConstructorCallStmt lowerConstructorCall(ExplicitConstructorCall tree) {
        boolean isSuper = tree.isSuperAccess();
        return new ConstructorCallStmt(isSuper,
                tree.qualification == null ? null : lowerExpr(tree.qualification),
                List.of(), mapExprs(tree.arguments), methodSymbol(tree.binding, null), range(tree));
    }

    private ForStmt lowerFor(ForStatement tree) {
        List<Node> init = new ArrayList<>();
        if (tree.initializations != null) {
            for (Statement stmt : tree.initializations) {
                if (stmt instanceof LocalDeclaration local) init.add(lowerLocal(local));
                else if (stmt instanceof Expression expr) init.add(lowerExpr(expr));
            }
        }
        List<ch.castleridge.javals.ast.Expression> update = new ArrayList<>();
        if (tree.increments != null) {
            for (Statement stmt : tree.increments) {
                if (stmt instanceof Expression expr) update.add(lowerExpr(expr));
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
        List<Node> resources = new ArrayList<>();
        if (tree.resources != null) {
            for (Statement resource : tree.resources) {
                if (resource instanceof LocalDeclaration local) resources.add(lowerLocal(local));
                else if (resource instanceof Expression expr) resources.add(lowerExpr(expr));
            }
        }
        List<CatchClause> catches = new ArrayList<>();
        if (tree.catchArguments != null) {
            for (int i = 0; i < tree.catchArguments.length; i++) {
                catches.add(new CatchClause(lowerParam(tree.catchArguments[i], false),
                        lowerBlock(tree.catchBlocks[i]), range(tree.catchArguments[i])));
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
        TypeNode type = tree.type == null ? new VarTypeNode(SourceRange.NONE) : lowerTypeRef(tree.type);
        if (type instanceof VarTypeNode varType && symbol != null) varType.setResolvedType(symbol.type());
        return new LocalDeclStmt(mods(tree.modifiers), annos(tree.annotations), type, List.of(fragment), range(tree));
    }

    private List<SwitchArm> lowerSwitchArms(Statement[] statements) {
        if (statements == null) return List.of();
        List<SwitchArm> arms = new ArrayList<>();
        List<CaseLabel> labels = new ArrayList<>();
        List<ch.castleridge.javals.ast.Statement> body = new ArrayList<>();
        int start = -1;
        for (Statement stmt : statements) {
            if (stmt instanceof CaseStatement cse) {
                if (!labels.isEmpty() || !body.isEmpty()) {
                    arms.add(new SwitchArm(labels, false, null, body,
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
            arms.add(new SwitchArm(labels, false, null, body,
                    start < 0 ? SourceRange.NONE : new SourceRange(start, source.length())));
        }
        return arms;
    }

    private ch.castleridge.javals.ast.Expression lowerExpr(Expression tree) {
        if (tree == null) return null;
        SourceRange r = range(tree);
        ch.castleridge.javals.ast.Expression lowered;
        if (tree instanceof SingleNameReference name) {
            Identifier ident = new Identifier(str(name.token), r, symbolOf(name.binding));
            lowered = new NameExpr(ident, r);
        } else if (tree instanceof QualifiedNameReference qual) {
            lowered = lowerQualifiedName(qual);
        } else if (tree instanceof FieldReference field) {
            Identifier ident = new Identifier(str(field.token), namePos(field.nameSourcePosition), symbolOf(field.binding));
            lowered = new Select(lowerExpr(field.receiver), ident, r);
        } else if (tree instanceof MessageSend send) {
            lowered = lowerCall(send);
        } else if (tree instanceof AllocationExpression alloc) {
            lowered = lowerNew(alloc);
        } else if (tree instanceof ArrayAllocationExpression array) {
            lowered = lowerNewArray(array);
        } else if (tree instanceof ArrayInitializer init) {
            lowered = new ArrayInitExpr(mapExprs(init.expressions), r);
        } else if (tree instanceof ArrayReference access) {
            lowered = new ArrayAccessExpr(lowerExpr(access.receiver), lowerExpr(access.position), r);
        } else if (tree instanceof Literal literal) {
            lowered = lowerLiteral(literal, r);
        } else if (tree instanceof BinaryExpression binary) {
            if (tree instanceof EqualExpression equal) {
                lowered = new BinaryExpr(equalOp(equal), lowerExpr(equal.left), lowerExpr(equal.right), r);
            } else if ((binary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT == OperatorIds.AND_AND
                    || (binary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT == OperatorIds.OR_OR
                    || true) {
                int op = (binary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
                if (op == OperatorIds.QUESTIONCOLON) {
                    // ConditionalExpression extends BinaryExpression in some versions; handle via fields if present
                    lowered = new BinaryExpr(binaryOp(op), lowerExpr(binary.left), lowerExpr(binary.right), r);
                } else {
                    lowered = new BinaryExpr(binaryOp(op), lowerExpr(binary.left), lowerExpr(binary.right), r);
                }
            } else {
                lowered = new ErroneousExpr(List.of(), r);
            }
        } else if (tree instanceof UnaryExpression unary) {
            int op = (unary.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            lowered = new UnaryExpr(unaryOp(op, false), lowerExpr(unary.expression), r);
        } else if (tree instanceof PrefixExpression prefix) {
            int op = (prefix.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            lowered = new UnaryExpr(unaryOp(op, false), lowerExpr(prefix.lhs), r);
        } else if (tree instanceof PostfixExpression postfix) {
            int op = (postfix.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT;
            lowered = new UnaryExpr(unaryOp(op, true), lowerExpr(postfix.lhs), r);
        } else if (tree instanceof Assignment assign) {
            AssignExpr.Op op = assign instanceof CompoundAssignment compound
                    ? assignOp((compound.bits & ASTNode.OperatorMASK) >> ASTNode.OperatorSHIFT)
                    : AssignExpr.Op.ASSIGN;
            lowered = new AssignExpr(op, lowerExpr(assign.lhs), lowerExpr(assign.expression), r);
        } else if (tree instanceof CastExpression cast) {
            lowered = new CastExpr(lowerTypeRef(cast.type), lowerExpr(cast.expression), r);
        } else if (tree instanceof InstanceOfExpression io) {
            lowered = new InstanceOfExpr(lowerExpr(io.expression), lowerTypeRef(io.type), null, r);
        } else if (tree instanceof ThisReference thisRef) {
            TypeNode qual = tree instanceof QualifiedThisReference q ? lowerTypeRef(q.qualification) : null;
            ThisExpr expr = new ThisExpr(qual, r);
            expr.setType(jtype(thisRef.resolvedType));
            lowered = expr;
        } else if (tree instanceof SuperReference) {
            SuperExpr expr = new SuperExpr(null, r);
            expr.setType(jtype(tree.resolvedType));
            lowered = expr;
        } else if (tree instanceof ClassLiteralAccess cl) {
            lowered = new ClassLiteralExpr(lowerTypeRef(cl.type), r);
        } else if (tree instanceof LambdaExpression lambda) {
            lowered = lowerLambda(lambda);
        } else if (tree instanceof ReferenceExpression ref) {
            lowered = lowerMemberRef(ref);
        } else if (tree instanceof SwitchExpression sw) {
            SwitchExpr expr = new SwitchExpr(lowerExpr(sw.expression), lowerSwitchArms(sw.statements), r);
            expr.setType(jtype(sw.resolvedType));
            lowered = expr;
        } else if (tree instanceof org.eclipse.jdt.internal.compiler.ast.ConditionalExpression cond) {
            lowered = new ConditionalExpr(lowerExpr(cond.condition), lowerExpr(cond.valueIfTrue),
                    lowerExpr(cond.valueIfFalse), r);
        } else if (tree instanceof TypeReference typeRef) {
            TypeNode type = lowerTypeRef(typeRef);
            lowered = new NameExpr(type instanceof TypeName tn && tn.simpleName() != null
                    ? tn.simpleName() : new Identifier(typeRef.toString(), r, null), r);
        } else {
            lowered = new ErroneousExpr(List.of(), r);
        }
        if (lowered != null && lowered.type() == JType.ERROR && tree.resolvedType != null) {
            lowered.setType(jtype(tree.resolvedType));
        }
        return lowered;
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
            current = current == null ? new NameExpr(ident, nr) : new Select(current, ident, span(current.range(), nr));
        }
        return current;
    }

    private CallExpr lowerCall(MessageSend tree) {
        Identifier name = new Identifier(str(tree.selector), namePos(tree.nameSourcePosition), symbolOf(tree.binding));
        CallExpr call = new CallExpr(tree.receiver == null || tree.receiver.isImplicitThis() ? null : lowerExpr(tree.receiver),
                name, List.of(), mapExprs(tree.arguments), range(tree));
        call.setType(jtype(tree.resolvedType));
        return call;
    }

    private NewExpr lowerNew(AllocationExpression tree) {
        TypeDecl body = null;
        if (tree instanceof QualifiedAllocationExpression q && q.anonymousType != null) {
            body = lowerType(q.anonymousType);
        }
        TypeNode type = lowerTypeRef(tree.type);
        MethodSymbol constructor = methodSymbol(tree.binding, null);
        bindConstructorName(type, constructor);
        NewExpr expr = new NewExpr(
                tree instanceof QualifiedAllocationExpression q ? lowerExpr(q.enclosingInstance()) : null,
                type, List.of(), mapExprs(tree.arguments), body,
                constructor, range(tree));
        expr.setType(jtype(tree.resolvedType));
        return expr;
    }

    private void bindConstructorName(TypeNode type, Symbol constructor) {
        if (constructor == null || type == null) return;
        Identifier name = switch (type) {
            case TypeName tn -> tn.simpleName();
            case ParameterizedTypeNode parameterized -> {
                bindConstructorName(parameterized.raw(), constructor);
                yield null;
            }
            default -> null;
        };
        if (name != null) name.setSymbol(constructor);
    }

    private NewArrayExpr lowerNewArray(ArrayAllocationExpression tree) {
        ArrayInitExpr init = tree.initializer == null ? null
                : new ArrayInitExpr(mapExprs(tree.initializer.expressions), range(tree.initializer));
        NewArrayExpr expr = new NewArrayExpr(lowerTypeRef(tree.type), mapExprs(tree.dimensions), init, range(tree));
        expr.setType(jtype(tree.resolvedType));
        return expr;
    }

    private LiteralExpr lowerLiteral(Literal tree, SourceRange r) {
        Constant constant = tree.constant;
        if (constant == null || constant == Constant.NotAConstant) {
            if (tree instanceof org.eclipse.jdt.internal.compiler.ast.NullLiteral) {
                return new LiteralExpr(LiteralExpr.Kind.NULL, null, slice(r), r);
            }
            return new LiteralExpr(LiteralExpr.Kind.STRING, tree.toString(), slice(r), r);
        }
        return switch (constant.typeID()) {
            case TypeIds.T_int -> new LiteralExpr(LiteralExpr.Kind.INT, constant.intValue(), slice(r), r);
            case TypeIds.T_long -> new LiteralExpr(LiteralExpr.Kind.LONG, constant.longValue(), slice(r), r);
            case TypeIds.T_float -> new LiteralExpr(LiteralExpr.Kind.FLOAT, constant.floatValue(), slice(r), r);
            case TypeIds.T_double -> new LiteralExpr(LiteralExpr.Kind.DOUBLE, constant.doubleValue(), slice(r), r);
            case TypeIds.T_char -> new LiteralExpr(LiteralExpr.Kind.CHAR, constant.charValue(), slice(r), r);
            case TypeIds.T_boolean -> new LiteralExpr(LiteralExpr.Kind.BOOLEAN, constant.booleanValue(), slice(r), r);
            case TypeIds.T_JavaLangString -> new LiteralExpr(LiteralExpr.Kind.STRING, constant.stringValue(), slice(r), r);
            default -> new LiteralExpr(LiteralExpr.Kind.NULL, null, slice(r), r);
        };
    }

    private LambdaExpr lowerLambda(LambdaExpression tree) {
        List<ParamDecl> params = new ArrayList<>();
        if (tree.arguments != null) {
            for (Argument arg : tree.arguments) params.add(lowerParam(arg, false));
        }
        ch.castleridge.javals.ast.Expression exprBody = null;
        ch.castleridge.javals.ast.Block blockBody = null;
        if (tree.body instanceof Expression expr) exprBody = lowerExpr(expr);
        else if (tree.body instanceof Block block) blockBody = lowerBlock(block);
        else if (tree.body instanceof Statement stmt) {
            blockBody = new ch.castleridge.javals.ast.Block(List.of(lowerStmt(stmt)), range(tree.body));
        }
        boolean elided = tree.arguments == null || tree.arguments.length == 0 || tree.arguments[0].type == null;
        LambdaExpr lambda = new LambdaExpr(params, elided, exprBody, blockBody, range(tree));
        lambda.setType(jtype(tree.resolvedType));
        return lambda;
    }

    private MemberRefExpr lowerMemberRef(ReferenceExpression tree) {
        Identifier name = new Identifier(str(tree.selector), range(tree), symbolOf(tree.binding));
        TypeNode type = tree.lhs instanceof TypeReference typeRef ? lowerTypeRef(typeRef) : null;
        ch.castleridge.javals.ast.Expression expr = type == null && tree.lhs != null ? lowerExpr(tree.lhs) : null;
        MemberRefExpr.Mode mode = tree.isConstructorReference() ? MemberRefExpr.Mode.NEW : MemberRefExpr.Mode.INVOKE;
        MemberRefExpr ref = new MemberRefExpr(expr, type, mode, name, List.of(), range(tree));
        ref.setType(jtype(tree.resolvedType));
        return ref;
    }

    private TypeNode lowerTypeRef(TypeReference tree) {
        if (tree == null) return null;
        SourceRange r = range(tree);
        TypeNode lowered;
        if (tree instanceof Wildcard wild) {
            lowered = switch (wild.kind) {
                case Wildcard.UNBOUND -> new WildcardTypeNode(WildcardTypeNode.BoundKind.UNBOUNDED, null, r);
                case Wildcard.EXTENDS -> new WildcardTypeNode(WildcardTypeNode.BoundKind.EXTENDS, lowerTypeRef(wild.bound), r);
                default -> new WildcardTypeNode(WildcardTypeNode.BoundKind.SUPER, lowerTypeRef(wild.bound), r);
            };
        } else if (tree instanceof ParameterizedSingleTypeReference param) {
            TypeName raw = typeName(List.of(new Identifier(str(param.token),
                    new SourceRange(param.sourceStart, param.sourceStart + param.token.length),
                    symbolOf(validType(param.resolvedType)))), r);
            List<TypeNode> args = new ArrayList<>();
            if (param.typeArguments != null) {
                for (TypeReference arg : param.typeArguments) args.add(lowerTypeRef(arg));
            }
            ParameterizedTypeNode node = new ParameterizedTypeNode(raw, args, r);
            node.setResolvedType(jtype(param.resolvedType));
            lowered = node;
        } else if (tree instanceof ParameterizedQualifiedTypeReference param) {
            TypeName raw = typeName(qualifiedTypeIdents(param.tokens, param.sourcePositions, param.resolvedType), r);
            List<TypeNode> args = List.of();
            if (param.typeArguments != null && param.typeArguments.length > 0
                    && param.typeArguments[param.typeArguments.length - 1] != null) {
                List<TypeNode> last = new ArrayList<>();
                for (TypeReference arg : param.typeArguments[param.typeArguments.length - 1]) {
                    last.add(lowerTypeRef(arg));
                }
                args = last;
            }
            ParameterizedTypeNode node = new ParameterizedTypeNode(raw, args, r);
            node.setResolvedType(jtype(param.resolvedType));
            lowered = node;
        } else if (tree instanceof ArrayTypeReference array) {
            TypeNode element = typeName(List.of(new Identifier(str(array.token),
                    new SourceRange(array.sourceStart, array.sourceStart + array.token.length),
                    symbolOf(validType(array.resolvedType)))), r);
            TypeNode current = element;
            int dims = array.dimensions();
            for (int i = 0; i < dims; i++) current = new ArrayTypeNode(current, r);
            lowered = current;
        } else if (tree instanceof ArrayQualifiedTypeReference array) {
            TypeNode element = typeName(qualifiedTypeIdents(array.tokens, array.sourcePositions, array.resolvedType), r);
            TypeNode current = element;
            int dims = array.dimensions();
            for (int i = 0; i < dims; i++) current = new ArrayTypeNode(current, r);
            lowered = current;
        } else if (tree instanceof QualifiedTypeReference qual) {
            lowered = typeName(qualifiedTypeIdents(qual.tokens, qual.sourcePositions, qual.resolvedType), r);
        } else if (tree instanceof SingleTypeReference single) {
            if (isPrimitive(single.token)) {
                lowered = primitive(single.token, r);
            } else {
                lowered = typeName(List.of(new Identifier(str(single.token), r,
                        symbolOf(validType(single.resolvedType)))), r);
            }
        } else {
            lowered = typeName(List.of(new Identifier(tree.toString(), r, symbolOf(validType(tree.resolvedType)))), r);
        }
        if (lowered != null && lowered.resolvedType() == JType.ERROR) lowered.setResolvedType(jtype(tree.resolvedType));
        return lowered;
    }

    private TypeName typeName(List<Identifier> names, SourceRange range) {
        TypeName node = new TypeName(names, range);
        if (!names.isEmpty() && names.get(names.size() - 1).symbol() != null) {
            node.setResolvedType(names.get(names.size() - 1).symbol().type());
        }
        return node;
    }

    private List<Identifier> qualifiedTypeIdents(char[][] tokens, long[] positions, TypeBinding resolved) {
        List<Identifier> out = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            SourceRange nr = positions == null || i >= positions.length
                    ? SourceRange.NONE : posRange(positions[i]);
            Binding binding = i == tokens.length - 1 ? validType(resolved) : null;
            out.add(new Identifier(new String(tokens[i]), nr, symbolOf(binding)));
        }
        return out;
    }

    private List<ch.castleridge.javals.ast.Annotation> annos(Annotation[] annotations) {
        if (annotations == null) return List.of();
        List<ch.castleridge.javals.ast.Annotation> out = new ArrayList<>();
        for (Annotation annotation : annotations) {
            TypeName name = annotation.type == null ? typeName(List.of(), SourceRange.NONE)
                    : (lowerTypeRef(annotation.type) instanceof TypeName tn ? tn
                    : typeName(List.of(), range(annotation.type)));
            List<AnnoArg> args = new ArrayList<>();
            if (annotation instanceof SingleMemberAnnotation single) {
                args.add(new AnnoArg(null, lowerExpr(single.memberValue), range(single.memberValue)));
            } else if (annotation instanceof NormalAnnotation normal && normal.memberValuePairs != null) {
                for (var pair : normal.memberValuePairs) {
                    args.add(new AnnoArg(new Identifier(str(pair.name), SourceRange.NONE, null),
                            lowerExpr(pair.value), range(pair)));
                }
            }
            out.add(new ch.castleridge.javals.ast.Annotation(name, args, range(annotation)));
        }
        return out;
    }

    private List<TypeNode> lowerTypeRefs(TypeReference[] refs) {
        if (refs == null) return List.of();
        List<TypeNode> out = new ArrayList<>();
        for (TypeReference ref : refs) out.add(lowerTypeRef(ref));
        return out;
    }

    private List<ch.castleridge.javals.ast.Expression> mapExprs(Expression[] exprs) {
        if (exprs == null) return List.of();
        List<ch.castleridge.javals.ast.Expression> out = new ArrayList<>();
        for (Expression expr : exprs) {
            ch.castleridge.javals.ast.Expression lowered = expr == null ? null : lowerExpr(expr);
            out.add(lowered == null ? new ErroneousExpr(List.of(), SourceRange.NONE) : lowered);
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
        TypeSymbol symbol = new TypeSymbol(kind, binary, astKey(ref), List.of());
        interned.put(ref, symbol);
        typesByJvm.put(jvm, symbol);
        return symbol;
    }

    private MethodSymbol methodSymbol(MethodBinding binding, TypeSymbol owner) {
        if (binding == null || !binding.isValidBinding()) return null;
        Symbol existing = interned.get(binding);
        if (existing instanceof MethodSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(binding.declaringClass);
        List<JType> params = new ArrayList<>();
        if (binding.parameters != null) {
            for (TypeBinding param : binding.parameters) params.add(jtype(param));
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
        TypeVarSymbol symbol = new TypeVarSymbol(new String(binding.sourceName), List.of());
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
                List<String> parameters = new ArrayList<>();
                if (method.parameters != null) {
                    for (TypeBinding parameter : method.parameters) {
                        parameters.add(new String(parameter.erasure().readableName()));
                    }
                }
                String key = "M:" + origin.get() + "|" + ownerJvm.replace('/', '.')
                        + "#" + name + "(" + String.join(",", parameters) + ")";
                return SymbolKey.of(key, simple, origin.get());
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

    private Optional<String> origin(String ownerJvm) {
        String indexed = indexedOrigin(ownerJvm);
        boolean declaredHere = declares(ownerJvm);
        if (indexed != null && (!declaredHere || FileUris.sameFile(uri, indexed))) {
            return Optional.of(indexed);
        }
        if (declaredHere) return Optional.of(uri);
        return Optional.empty();
    }

    private String indexedOrigin(String ownerJvm) {
        if (index == null || ownerJvm == null) return null;
        TypeEntry entry = classpath.pick(index.getAll(ownerJvm), TypeEntry::sourceUri);
        if (entry == null) return null;
        String resourceUri = entry.resourceUri();
        if (resourceUri == null || resourceUri.isBlank()) return null;
        return resourceUri;
    }

    private boolean declares(String ownerJvm) {
        if (unit.types == null) return false;
        return declares(unit.types, ownerJvm);
    }

    private static boolean declares(TypeDeclaration[] types, String ownerJvm) {
        if (types == null) return false;
        for (TypeDeclaration type : types) {
            if (type.binding != null && ownerJvm.equals(new String(type.binding.constantPoolName()))) return true;
            if (declares(type.memberTypes, ownerJvm)) return true;
        }
        return false;
    }

    private JType jtype(TypeBinding binding) {
        if (binding == null || !binding.isValidBinding()) return JType.ERROR;
        if (binding.isPrimitiveType()) {
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
                default -> JType.ERROR;
            };
        }
        if (binding.isArrayType()) return JType.array(jtype(binding.leafComponentType()));
        if (binding.isTypeVariable()) return new JType.TypeVar(new String(binding.sourceName()));
        if (binding instanceof ReferenceBinding ref) {
            char[] name = ref.erasure().constantPoolName();
            if (name == null) return JType.ERROR;
            return JType.Declared.of(new String(name));
        }
        return JType.ERROR;
    }

    private TypeBinding validType(TypeBinding binding) {
        return binding != null && binding.isValidBinding() ? binding : null;
    }

    private TypeDeclKind typeKind(TypeDeclaration tree) {
        int kind = TypeDeclaration.kind(tree.modifiers);
        if (kind == TypeDeclaration.INTERFACE_DECL) return TypeDeclKind.INTERFACE;
        if (kind == TypeDeclaration.ENUM_DECL) return TypeDeclKind.ENUM;
        if (kind == TypeDeclaration.ANNOTATION_TYPE_DECL) return TypeDeclKind.ANNOTATION;
        try {
            if (kind == TypeDeclaration.RECORD_DECL) return TypeDeclKind.RECORD;
        } catch (NoSuchFieldError ignored) {
            if (tree.binding != null && tree.binding.isRecord()) return TypeDeclKind.RECORD;
        }
        return TypeDeclKind.CLASS;
    }

    private Set<Modifier> mods(int bits) {
        EnumSet<Modifier> out = EnumSet.noneOf(Modifier.class);
        if ((bits & ClassFileConstants.AccPublic) != 0) out.add(Modifier.PUBLIC);
        if ((bits & ClassFileConstants.AccProtected) != 0) out.add(Modifier.PROTECTED);
        if ((bits & ClassFileConstants.AccPrivate) != 0) out.add(Modifier.PRIVATE);
        if ((bits & ClassFileConstants.AccAbstract) != 0) out.add(Modifier.ABSTRACT);
        if ((bits & ClassFileConstants.AccStatic) != 0) out.add(Modifier.STATIC);
        if ((bits & ClassFileConstants.AccFinal) != 0) out.add(Modifier.FINAL);
        if ((bits & ClassFileConstants.AccNative) != 0) out.add(Modifier.NATIVE);
        if ((bits & ClassFileConstants.AccSynchronized) != 0) out.add(Modifier.SYNCHRONIZED);
        if ((bits & ClassFileConstants.AccTransient) != 0) out.add(Modifier.TRANSIENT);
        if ((bits & ClassFileConstants.AccVolatile) != 0) out.add(Modifier.VOLATILE);
        if ((bits & ExtraCompilerModifiers.AccDefaultMethod) != 0) out.add(Modifier.DEFAULT);
        if ((bits & ExtraCompilerModifiers.AccSealed) != 0) out.add(Modifier.SEALED);
        if ((bits & ExtraCompilerModifiers.AccNonSealed) != 0) out.add(Modifier.NON_SEALED);
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    private SourceRange range(ASTNode node) {
        if (node == null) return SourceRange.NONE;
        return new SourceRange(Math.max(0, node.sourceStart), inclusiveEnd(node.sourceEnd));
    }

    private int inclusiveEnd(int sourceEnd) {
        return Math.min(source.length(), Math.max(0, sourceEnd) + 1);
    }

    private SourceRange nameRange(char[] name, int start, int end) {
        if (name == null) return new SourceRange(Math.max(0, start), inclusiveEnd(end));
        int found = source.indexOf(new String(name), Math.max(0, start));
        if (found < 0 || found > end) return new SourceRange(Math.max(0, start), Math.min(source.length(), start + name.length));
        return new SourceRange(found, found + name.length);
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

    private TypeNode primitive(char[] token, SourceRange range) {
        String n = str(token);
        if ("void".equals(n)) return new VoidTypeNode(range);
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
        return new PrimitiveTypeNode(kind, range);
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
            case OperatorIds.PLUS -> AssignExpr.Op.PLUS;
            case OperatorIds.MINUS -> AssignExpr.Op.MINUS;
            case OperatorIds.MULTIPLY -> AssignExpr.Op.MULTIPLY;
            case OperatorIds.DIVIDE -> AssignExpr.Op.DIVIDE;
            case OperatorIds.REMAINDER -> AssignExpr.Op.REMAINDER;
            case OperatorIds.AND -> AssignExpr.Op.AND;
            case OperatorIds.OR -> AssignExpr.Op.OR;
            case OperatorIds.XOR -> AssignExpr.Op.XOR;
            case OperatorIds.LEFT_SHIFT -> AssignExpr.Op.LEFT_SHIFT;
            case OperatorIds.RIGHT_SHIFT -> AssignExpr.Op.RIGHT_SHIFT;
            case OperatorIds.UNSIGNED_RIGHT_SHIFT -> AssignExpr.Op.UNSIGNED_RIGHT_SHIFT;
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
        static final int T_JavaLangString = org.eclipse.jdt.internal.compiler.lookup.TypeIds.T_JavaLangString;
    }

}
