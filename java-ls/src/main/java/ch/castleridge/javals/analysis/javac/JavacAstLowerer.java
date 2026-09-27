/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.javac;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ExportsTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.OpensTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PatternTree;
import com.sun.source.tree.ProvidesTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.UsesTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;

import ch.castleridge.javals.ast.AnnoArg;
import ch.castleridge.javals.ast.Annotation;
import ch.castleridge.javals.ast.ArrayAccessExpr;
import ch.castleridge.javals.ast.ArrayInitExpr;
import ch.castleridge.javals.ast.ArrayTypeNode;
import ch.castleridge.javals.ast.AssertStmt;
import ch.castleridge.javals.ast.AssignExpr;
import ch.castleridge.javals.ast.BinaryExpr;
import ch.castleridge.javals.ast.Block;
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
import ch.castleridge.javals.ast.ErroneousType;
import ch.castleridge.javals.ast.ExportsDirective;
import ch.castleridge.javals.ast.ExprStmt;
import ch.castleridge.javals.ast.Expression;
import ch.castleridge.javals.ast.FieldDecl;
import ch.castleridge.javals.ast.FieldSymbol;
import ch.castleridge.javals.ast.ForEachStmt;
import ch.castleridge.javals.ast.ForStmt;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.IfStmt;
import ch.castleridge.javals.ast.ImportDecl;
import ch.castleridge.javals.ast.InitializerDecl;
import ch.castleridge.javals.ast.InstanceOfExpr;
import ch.castleridge.javals.ast.IntersectionTypeNode;
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
import ch.castleridge.javals.ast.ModuleDecl;
import ch.castleridge.javals.ast.ModuleDirective;
import ch.castleridge.javals.ast.ModuleSymbol;
import ch.castleridge.javals.ast.NameExpr;
import ch.castleridge.javals.ast.NewArrayExpr;
import ch.castleridge.javals.ast.NewExpr;
import ch.castleridge.javals.ast.Node;
import ch.castleridge.javals.ast.OpensDirective;
import ch.castleridge.javals.ast.PackageDecl;
import ch.castleridge.javals.ast.PackageSymbol;
import ch.castleridge.javals.ast.ParamDecl;
import ch.castleridge.javals.ast.ParameterizedTypeNode;
import ch.castleridge.javals.ast.ParenthesizedExpr;
import ch.castleridge.javals.ast.Pattern;
import ch.castleridge.javals.ast.PatternLabel;
import ch.castleridge.javals.ast.PrimitiveTypeNode;
import ch.castleridge.javals.ast.ProvidesDirective;
import ch.castleridge.javals.ast.ReceiverParam;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.RecordComponentSymbol;
import ch.castleridge.javals.ast.RecordPattern;
import ch.castleridge.javals.ast.RequiresDirective;
import ch.castleridge.javals.ast.ReturnStmt;
import ch.castleridge.javals.ast.Select;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.Statement;
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
import ch.castleridge.javals.ast.TypePattern;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.TypeNode;
import ch.castleridge.javals.ast.TypeVarSymbol;
import ch.castleridge.javals.ast.UnaryExpr;
import ch.castleridge.javals.ast.UnionTypeNode;
import ch.castleridge.javals.ast.UnnamedPattern;
import ch.castleridge.javals.ast.UsesDirective;
import ch.castleridge.javals.ast.VarFragment;
import ch.castleridge.javals.ast.VarTypeNode;
import ch.castleridge.javals.ast.VoidTypeNode;
import ch.castleridge.javals.ast.WhileStmt;
import ch.castleridge.javals.ast.WildcardTypeNode;
import ch.castleridge.javals.ast.YieldStmt;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;

final class JavacAstLowerer {

    private final CompilationUnitTree cu;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final SourcePositions positions;
    private final String source;
    private final Index index;
    private final ClasspathOrder classpath;
    private final Map<String, String> sourceJarByBinaryJar;
    private final Map<Element, Symbol> interned = new IdentityHashMap<>();

    private JavacAstLowerer(CompilationUnitTree cu,
                            Trees trees,
                            Elements elements,
                            Types types,
                            String source,
                            Index index,
                            ClasspathOrder classpath,
                            Map<String, String> sourceJarByBinaryJar) {
        this.cu = cu;
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.positions = trees == null ? null : trees.getSourcePositions();
        this.source = source == null ? "" : source;
        this.index = index;
        this.classpath = classpath;
        this.sourceJarByBinaryJar = sourceJarByBinaryJar == null ? Map.of() : sourceJarByBinaryJar;
    }

    static CompilationUnit lower(JavacWorkspaceCompiler.Result result,
                                 String uri,
                                 CharSequence text,
                                 Index index,
                                 ClasspathOrder classpath,
                                 Map<String, String> sourceJarByBinaryJar) {
        String source = text == null ? "" : text.toString();
        if (result == null || result.cu() == null) {
            return new CompilationUnit(new SourceFile(uri, source), null, List.of(), List.of(), null,
                    new SourceRange(0, source.length()));
        }
        Elements elements = result.task() == null ? null : result.task().getElements();
        Types typesUtil = result.task() == null ? null : result.task().getTypes();
        return new JavacAstLowerer(result.cu(), result.trees(), elements, typesUtil, source, index, classpath,
                sourceJarByBinaryJar).lowerUnit(uri);
    }

    static CompilationUnit diet(String uri, String text, CompilationUnitTree cu, Trees trees) {
        String source = text == null ? "" : text;
        if (cu == null) {
            return new CompilationUnit(new SourceFile(uri, source), null, List.of(), List.of(), null,
                    new SourceRange(0, source.length()));
        }
        return new JavacAstLowerer(cu, trees, null, null, source, null, null, Map.of()).lowerUnit(uri);
    }

    private CompilationUnit lowerUnit(String uri) {
        PackageDecl pkg = cu.getPackage() == null ? null : lowerPackage(cu.getPackage());
        List<ImportDecl> imports = new ArrayList<>();
        for (ImportTree imp : cu.getImports()) imports.add(lowerImport(imp));
        List<TypeDecl> types = new ArrayList<>();
        for (Tree type : cu.getTypeDecls()) {
            if (type instanceof ClassTree classTree) types.add(lowerType(classTree));
        }
        ModuleDecl module = cu.getModule() == null ? null : lowerModule(cu.getModule());
        return new CompilationUnit(new SourceFile(uri == null ? "" : uri, source),
                pkg, imports, types, module, range(cu));
    }

    private PackageDecl lowerPackage(PackageTree tree) {
        Tree name = tree.getPackageName();
        List<Identifier> names = name instanceof ExpressionTree expr ? typeNameIdents(expr) : List.of();
        return new PackageDecl(annos(tree.getAnnotations()), names, range(tree));
    }

    private ImportDecl lowerImport(ImportTree tree) {
        Tree qual = tree.getQualifiedIdentifier();
        List<Identifier> names = qual instanceof ExpressionTree expr ? typeNameIdents(expr) : List.of();
        boolean onDemand = qual instanceof MemberSelectTree select
                && "*".equals(select.getIdentifier().toString());
        if (tree.isStatic() && !onDemand && !names.isEmpty()) {
            names = withStaticImportSymbol(names, qual);
        }
        return new ImportDecl(tree.isStatic(), onDemand, names, range(tree));
    }

    /** Rebuild the last Identifier so it carries the static member symbol (immutable). */
    private List<Identifier> withStaticImportSymbol(List<Identifier> names, Tree qual) {
        if (names.size() < 2) return names;
        Identifier last = names.get(names.size() - 1);
        if ("*".equals(last.name())) return names;
        ExpressionTree ownerTree = qual instanceof MemberSelectTree select ? select.getExpression() : null;
        Element owner = elementOf(ownerTree);
        if (!(owner instanceof TypeElement type)) return names;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getSimpleName().contentEquals(last.name())
                    && enclosed.getModifiers().contains(Modifier.STATIC)) {
                List<Identifier> rebound = new ArrayList<>(names);
                rebound.set(rebound.size() - 1, new Identifier(last.name(), last.range(), symbolOf(enclosed)));
                return rebound;
            }
        }
        return names;
    }

    private TypeDecl lowerType(ClassTree tree) {
        TypeDeclKind kind = switch (tree.getKind()) {
            case INTERFACE -> TypeDeclKind.INTERFACE;
            case ENUM -> TypeDeclKind.ENUM;
            case ANNOTATION_TYPE -> TypeDeclKind.ANNOTATION;
            case RECORD -> TypeDeclKind.RECORD;
            default -> TypeDeclKind.CLASS;
        };
        TypeSymbol symbol = typeSymbol(elementOf(tree));
        Identifier name = ident(tree.getSimpleName().toString(), nameRange(tree, tree.getSimpleName().toString()), symbol);
        List<TypeParamDecl> typeParams = lowerTypeParams(tree.getTypeParameters());
        TypeNode superclass = tree.getExtendsClause() == null ? null : lowerTypeNode(tree.getExtendsClause());
        List<TypeNode> interfaces = mapTypes(tree.getImplementsClause());
        List<TypeNode> permits = mapTypes(tree.getPermitsClause());
        List<RecordComponentDecl> components = new ArrayList<>();
        List<Declaration> members = new ArrayList<>();
        for (Tree member : tree.getMembers()) {
            if (member instanceof VariableTree variable && isRecordComponent(variable)) {
                components.add(lowerRecordComponent(variable, symbol));
            } else {
                Declaration decl = lowerMember(member, symbol, kind);
                if (decl != null) members.add(decl);
            }
        }
        return new TypeDecl(kind, mods(tree.getModifiers()), annos(tree.getModifiers()), name,
                typeParams, superclass, interfaces, permits, components, members, symbol, range(tree));
    }

    private boolean isRecordComponent(VariableTree tree) {
        Element element = elementOf(tree);
        return element != null && element.getKind() == ElementKind.RECORD_COMPONENT;
    }

    private Declaration lowerMember(Tree tree, TypeSymbol owner, TypeDeclKind ownerKind) {
        if (tree instanceof ClassTree nested) return lowerType(nested);
        if (tree instanceof MethodTree method) return lowerMethod(method, owner, ownerKind);
        if (tree instanceof VariableTree variable) {
            if (ownerKind == TypeDeclKind.ENUM && isEnumConstant(variable)) {
                return lowerEnumConstant(variable, owner);
            }
            return lowerField(variable, owner);
        }
        if (tree instanceof BlockTree block) {
            return new InitializerDecl(block.isStatic(), lowerBlock(block), range(block));
        }
        return null;
    }

    private boolean isEnumConstant(VariableTree tree) {
        Element element = elementOf(tree);
        return element != null && element.getKind() == ElementKind.ENUM_CONSTANT;
    }

    private Declaration lowerMethod(MethodTree tree, TypeSymbol owner, TypeDeclKind ownerKind) {
        Element element = elementOf(tree);
        MethodSymbol symbol = methodSymbol(element, owner);
        String rawName = tree.getName().toString();
        boolean constructor = element != null && element.getKind() == ElementKind.CONSTRUCTOR
                || rawName.equals("<init>")
                || (owner != null && rawName.equals(owner.name()));
        Identifier name = ident(constructor && owner != null ? owner.name() : rawName,
                nameRange(tree, constructor && owner != null ? owner.name() : rawName), symbol);
        List<TypeParamDecl> typeParams = lowerTypeParams(tree.getTypeParameters());
        ReceiverParam receiver = tree.getReceiverParameter() == null
                ? null : lowerReceiver(tree.getReceiverParameter());
        List<ParamDecl> params = new ArrayList<>();
        List<? extends VariableTree> parameters = tree.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            params.add(lowerParam(parameters.get(i), i == parameters.size() - 1 && isVarargs(element)));
        }
        List<TypeNode> thrown = mapTypes(tree.getThrows());
        Block body = tree.getBody() == null ? null : lowerBlock(tree.getBody());
        if (constructor && isCompactConstructor(element) && ownerKind == TypeDeclKind.RECORD) {
            return new CompactConstructorDecl(mods(tree.getModifiers()), annos(tree.getModifiers()),
                    name, body, symbol, range(tree));
        }
        if (constructor) {
            return new ConstructorDecl(mods(tree.getModifiers()), annos(tree.getModifiers()), typeParams,
                    name, receiver, params, thrown, body, symbol, range(tree));
        }
        TypeNode returnType = tree.getReturnType() == null ? new VoidTypeNode(null, SourceRange.NONE)
                : lowerTypeNode(tree.getReturnType());
        return new MethodDecl(mods(tree.getModifiers()), annos(tree.getModifiers()), typeParams,
                returnType, name, receiver, params, thrown, body, symbol, range(tree));
    }

    private boolean isCompactConstructor(Element element) {
        return element instanceof com.sun.tools.javac.code.Symbol.MethodSymbol method
                && (method.flags() & Flags.COMPACT_RECORD_CONSTRUCTOR) != 0;
    }

    private boolean isVarargs(Element element) {
        return element instanceof ExecutableElement executable && executable.isVarArgs();
    }

    private FieldDecl lowerField(VariableTree tree, TypeSymbol owner) {
        TypeNode type = lowerTypeNode(tree.getType());
        Symbol symbol = fieldSymbol(elementOf(tree), owner);
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        Expression init = tree.getInitializer() == null ? null : lowerExpr(tree.getInitializer());
        VarFragment fragment = new VarFragment(name, 0, init, symbol, range(tree));
        return new FieldDecl(mods(tree.getModifiers()), annos(tree.getModifiers()), type, List.of(fragment), range(tree));
    }

    private EnumConstantDecl lowerEnumConstant(VariableTree tree, TypeSymbol owner) {
        EnumConstantSymbol symbol = enumSymbol(elementOf(tree), owner);
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        List<Expression> args = List.of();
        TypeDecl body = null;
        if (tree.getInitializer() instanceof NewClassTree created) {
            args = mapExprs(created.getArguments());
            if (created.getClassBody() != null) body = lowerType(created.getClassBody());
        }
        return new EnumConstantDecl(annos(tree.getModifiers()), name, args, body, symbol, range(tree));
    }

    private RecordComponentDecl lowerRecordComponent(VariableTree tree, TypeSymbol owner) {
        TypeNode type = lowerTypeNode(tree.getType());
        RecordComponentSymbol symbol = recordComponentSymbol(elementOf(tree), owner);
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        return new RecordComponentDecl(annos(tree.getModifiers()), type, name, false, symbol, range(tree));
    }

    private ParamDecl lowerParam(VariableTree tree, boolean varargs) {
        LocalSymbol symbol = localSymbol(elementOf(tree));
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        return new ParamDecl(mods(tree.getModifiers()), annos(tree.getModifiers()),
                lowerTypeNode(tree.getType()), name, varargs, symbol, range(tree));
    }

    private ReceiverParam lowerReceiver(VariableTree tree) {
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), null);
        return new ReceiverParam(annos(tree.getModifiers()), lowerTypeNode(tree.getType()), name, range(tree));
    }

    private List<TypeParamDecl> lowerTypeParams(List<? extends TypeParameterTree> trees) {
        List<TypeParamDecl> out = new ArrayList<>();
        for (TypeParameterTree tree : trees) out.add(lowerTypeParam(tree));
        return out;
    }

    private TypeParamDecl lowerTypeParam(TypeParameterTree tree) {
        TypeVarSymbol symbol = typeVarSymbol(elementOf(tree));
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        return new TypeParamDecl(annos(tree.getAnnotations()), name, mapTypes(tree.getBounds()), symbol, range(tree));
    }

    private Block lowerBlock(BlockTree tree) {
        List<Statement> statements = new ArrayList<>();
        for (StatementTree stmt : tree.getStatements()) statements.add(lowerStmt(stmt));
        return new Block(tree.isStatic(), statements, range(tree));
    }

    private Statement lowerStmt(StatementTree tree) {
        if (tree == null) return null;
        SourceRange range = range(tree);
        return switch (tree) {
            case BlockTree block -> lowerBlock(block);
            case EmptyStatementTree ignored -> new EmptyStmt(range);
            case ExpressionStatementTree expr -> lowerExprStmt(expr);
            case VariableTree variable -> lowerLocal(variable);
            case ClassTree nested -> new LocalTypeStmt(lowerType(nested), range);
            case IfTree iff -> new IfStmt(lowerExpr(iff.getCondition()), lowerStmt(iff.getThenStatement()),
                    iff.getElseStatement() == null ? null : lowerStmt(iff.getElseStatement()), range);
            case WhileLoopTree loop -> new WhileStmt(lowerExpr(loop.getCondition()), lowerStmt(loop.getStatement()), range);
            case DoWhileLoopTree loop -> new DoWhileStmt(lowerStmt(loop.getStatement()), lowerExpr(loop.getCondition()), range);
            case ForLoopTree loop -> lowerFor(loop);
            case EnhancedForLoopTree loop -> lowerForEach(loop);
            case TryTree tryTree -> lowerTry(tryTree);
            case SynchronizedTree sync -> new SynchronizedStmt(lowerExpr(sync.getExpression()),
                    lowerBlock(sync.getBlock()), range);
            case ReturnTree ret -> new ReturnStmt(ret.getExpression() == null ? null : lowerExpr(ret.getExpression()), range);
            case ThrowTree thr -> new ThrowStmt(lowerExpr(thr.getExpression()), range);
            case BreakTree brk -> new BreakStmt(label(brk.getLabel()), range);
            case ContinueTree cont -> new ContinueStmt(label(cont.getLabel()), range);
            case YieldTree yield -> new YieldStmt(lowerExpr(yield.getValue()), range);
            case AssertTree assertTree -> new AssertStmt(lowerExpr(assertTree.getCondition()),
                    assertTree.getDetail() == null ? null : lowerExpr(assertTree.getDetail()), range);
            case LabeledStatementTree labeled -> new LabeledStmt(
                    ident(labeled.getLabel().toString(), nameRange(labeled, labeled.getLabel().toString()), null),
                    lowerStmt(labeled.getStatement()), range);
            case SwitchTree sw -> new SwitchStmt(lowerExpr(sw.getExpression()), lowerArms(sw.getCases()), range);
            case ErroneousTree err -> new ErroneousStmt(lowerErroneous(err), range);
            default -> new ErroneousStmt(List.of(), range);
        };
    }

    private Statement lowerExprStmt(ExpressionStatementTree tree) {
        ExpressionTree expr = tree.getExpression();
        if (expr instanceof MethodInvocationTree call) {
            ExpressionTree select = call.getMethodSelect();
            String name = invocationName(select);
            if ("this".equals(name) || "super".equals(name)) {
                Expression qualifier = select instanceof MemberSelectTree member
                        ? lowerExpr(member.getExpression()) : null;
                return new ConstructorCallStmt("super".equals(name), qualifier,
                        mapTypes(call.getTypeArguments()), mapExprs(call.getArguments()),
                        methodSymbol(elementOf(call), null), range(tree));
            }
        }
        return new ExprStmt(lowerExpr(expr), range(tree));
    }

    private ForStmt lowerFor(ForLoopTree tree) {
        List<Node> init = new ArrayList<>();
        for (StatementTree stmt : tree.getInitializer()) {
            if (stmt instanceof VariableTree variable) init.add(lowerLocal(variable));
            else if (stmt instanceof ExpressionStatementTree expr) init.add(lowerExpr(expr.getExpression()));
        }
        List<Expression> update = new ArrayList<>();
        for (ExpressionStatementTree stmt : tree.getUpdate()) update.add(lowerExpr(stmt.getExpression()));
        return new ForStmt(init, tree.getCondition() == null ? null : lowerExpr(tree.getCondition()),
                update, lowerStmt(tree.getStatement()), range(tree));
    }

    private ForEachStmt lowerForEach(EnhancedForLoopTree tree) {
        return new ForEachStmt(lowerLocal(tree.getVariable()), lowerExpr(tree.getExpression()),
                lowerStmt(tree.getStatement()), range(tree));
    }

    private TryStmt lowerTry(TryTree tree) {
        List<Node> resources = new ArrayList<>();
        for (Tree resource : tree.getResources()) {
            if (resource instanceof VariableTree variable) resources.add(lowerLocal(variable));
            else if (resource instanceof ExpressionTree expr) resources.add(lowerExpr(expr));
        }
        List<CatchClause> catches = new ArrayList<>();
        for (CatchTree catchTree : tree.getCatches()) {
            catches.add(new CatchClause(lowerParam(catchTree.getParameter(), false),
                    lowerBlock(catchTree.getBlock()), range(catchTree)));
        }
        Block fin = tree.getFinallyBlock() == null ? null : lowerBlock(tree.getFinallyBlock());
        return new TryStmt(resources, lowerBlock(tree.getBlock()), catches, fin, range(tree));
    }

    private LocalDeclStmt lowerLocal(VariableTree tree) {
        LocalSymbol symbol = localSymbol(elementOf(tree));
        Identifier name = ident(tree.getName().toString(), nameRange(tree, tree.getName().toString()), symbol);
        Expression init = tree.getInitializer() == null ? null : lowerExpr(tree.getInitializer());
        VarFragment fragment = new VarFragment(name, 0, init, symbol, range(tree));
        TypeNode type;
        if (tree.getType() == null) {
            type = new VarTypeNode(symbol != null ? symbol.type() : null, SourceRange.NONE);
        } else {
            type = lowerTypeNode(tree.getType());
            if (type instanceof VarTypeNode && symbol != null) {
                type = new VarTypeNode(symbol.type(), type.range());
            }
        }
        return new LocalDeclStmt(mods(tree.getModifiers()), annos(tree.getModifiers()), type, List.of(fragment), range(tree));
    }

    private List<SwitchArm> lowerArms(List<? extends CaseTree> cases) {
        List<SwitchArm> out = new ArrayList<>();
        for (CaseTree cse : cases) out.add(lowerArm(cse));
        return out;
    }

    private SwitchArm lowerArm(CaseTree tree) {
        List<CaseLabel> labels = new ArrayList<>();
        try {
            for (CaseLabelTree label : tree.getLabels()) labels.add(lowerCaseLabel(label, tree));
        } catch (UnsupportedOperationException | Error ignored) {
            if (tree.getExpression() != null) {
                labels.add(new ConstantLabel(lowerExpr(tree.getExpression()), range(tree.getExpression())));
            } else {
                labels.add(new DefaultLabel(range(tree)));
            }
        }
        boolean arrow = tree.getCaseKind() == CaseTree.CaseKind.RULE;
        Expression exprBody = null;
        List<Statement> statements = new ArrayList<>();
        if (arrow && tree.getBody() instanceof ExpressionTree expr) {
            exprBody = lowerExpr(expr);
        } else if (tree.getBody() instanceof StatementTree stmt) {
            statements.add(lowerStmt(stmt));
        } else {
            for (StatementTree stmt : tree.getStatements()) statements.add(lowerStmt(stmt));
        }
        return new SwitchArm(labels, arrow, exprBody, statements, range(tree));
    }

    private CaseLabel lowerCaseLabel(CaseLabelTree tree, CaseTree parent) {
        if (tree.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) return new DefaultLabel(range(tree));
        if (tree instanceof PatternTree pattern) {
            return new PatternLabel(lowerPattern(pattern),
                    parent.getGuard() == null ? null : lowerExpr(parent.getGuard()), range(tree));
        }
        if (tree instanceof ExpressionTree expr) {
            return new ConstantLabel(lowerExpr(expr), range(tree));
        }
        return new DefaultLabel(range(tree));
    }

    private Expression lowerExpr(ExpressionTree tree) {
        if (tree == null) return null;
        SourceRange range = range(tree);
        JType type = jtype(typeOf(tree));
        return switch (tree) {
            case IdentifierTree ident -> lowerIdentExpr(ident, type, range);
            case MemberSelectTree select -> lowerSelect(select, type, range);
            case MethodInvocationTree call -> lowerCall(call);
            case NewClassTree created -> lowerNew(created);
            case NewArrayTree array -> lowerNewArray(array);
            case LiteralTree literal -> lowerLiteral(literal);
            case BinaryTree binary -> new BinaryExpr(binaryOp(binary.getKind()),
                    lowerExpr(binary.getLeftOperand()), lowerExpr(binary.getRightOperand()), type, range);
            case UnaryTree unary -> new UnaryExpr(unaryOp(unary.getKind()),
                    lowerExpr(unary.getExpression()), type, range);
            case AssignmentTree assign -> new AssignExpr(AssignExpr.Op.ASSIGN,
                    lowerExpr(assign.getVariable()), lowerExpr(assign.getExpression()), type, range);
            case CompoundAssignmentTree assign -> new AssignExpr(assignOp(assign.getKind()),
                    lowerExpr(assign.getVariable()), lowerExpr(assign.getExpression()), type, range);
            case ConditionalExpressionTree cond -> new ConditionalExpr(lowerExpr(cond.getCondition()),
                    lowerExpr(cond.getTrueExpression()), lowerExpr(cond.getFalseExpression()), type, range);
            case TypeCastTree cast -> new CastExpr(lowerTypeNode(cast.getType()),
                    lowerExpr(cast.getExpression()), type, range);
            case InstanceOfTree io -> lowerInstanceOf(io);
            case ArrayAccessTree access -> new ArrayAccessExpr(lowerExpr(access.getExpression()),
                    lowerExpr(access.getIndex()), type, range);
            case ParenthesizedTree parens -> new ParenthesizedExpr(lowerExpr(parens.getExpression()), type, range);
            case LambdaExpressionTree lambda -> lowerLambda(lambda);
            case MemberReferenceTree ref -> lowerMemberRef(ref);
            case SwitchExpressionTree sw -> new SwitchExpr(lowerExpr(sw.getExpression()),
                    lowerArms(sw.getCases()), type, range);
            case AnnotationTree anno -> lowerAnnotation(anno);
            case ErroneousTree err -> new ErroneousExpr(lowerErroneous(err), type, range);
            default -> {
                if (tree.getKind() == Tree.Kind.PRIMITIVE_TYPE
                        || tree.getKind() == Tree.Kind.ARRAY_TYPE
                        || tree.getKind() == Tree.Kind.PARAMETERIZED_TYPE
                        || tree.getKind() == Tree.Kind.UNBOUNDED_WILDCARD
                        || tree.getKind() == Tree.Kind.EXTENDS_WILDCARD
                        || tree.getKind() == Tree.Kind.SUPER_WILDCARD) {
                    yield classLiteralOrType(tree, type, range);
                }
                yield new ErroneousExpr(List.of(), type, range);
            }
        };
    }

    private Expression classLiteralOrType(ExpressionTree tree, JType type, SourceRange range) {
        return new ErroneousExpr(List.of(), type, range);
    }

    private Expression lowerIdentExpr(IdentifierTree tree, JType type, SourceRange range) {
        String name = tree.getName().toString();
        if ("this".equals(name)) {
            return new ThisExpr(null, type, range);
        }
        if ("super".equals(name)) {
            return new SuperExpr(null, type, range);
        }
        if ("class".equals(name)) {
            return new ClassLiteralExpr(null, type, range);
        }
        Identifier ident = ident(name, range, symbolOf(elementOf(tree)));
        return new NameExpr(ident, type, range);
    }

    private Expression lowerSelect(MemberSelectTree tree, JType type, SourceRange range) {
        String name = tree.getIdentifier().toString();
        if ("class".equals(name)) {
            return new ClassLiteralExpr(lowerTypeNode(tree.getExpression()), type, range);
        }
        if ("this".equals(name)) {
            return new ThisExpr(lowerTypeNode(tree.getExpression()), type, range);
        }
        if ("super".equals(name)) {
            return new SuperExpr(lowerTypeNode(tree.getExpression()), type, range);
        }
        Identifier ident = ident(name, selectNameRange(tree), symbolOf(elementOf(tree)));
        return new Select(lowerExpr(tree.getExpression()), ident, type, range);
    }

    private CallExpr lowerCall(MethodInvocationTree tree) {
        ExpressionTree select = tree.getMethodSelect();
        Expression receiver = null;
        Identifier name;
        if (select instanceof MemberSelectTree member) {
            receiver = lowerExpr(member.getExpression());
            name = ident(member.getIdentifier().toString(), selectNameRange(member), symbolOf(elementOf(tree)));
        } else if (select instanceof IdentifierTree ident) {
            name = ident(ident.getName().toString(), range(ident), symbolOf(elementOf(tree)));
        } else {
            name = ident(invocationName(select), range(select), symbolOf(elementOf(tree)));
        }
        return new CallExpr(receiver, name, mapTypes(tree.getTypeArguments()),
                mapExprs(tree.getArguments()), jtype(typeOf(tree)), range(tree));
    }

    private String invocationName(ExpressionTree tree) {
        if (tree instanceof IdentifierTree ident) return ident.getName().toString();
        if (tree instanceof MemberSelectTree select) return select.getIdentifier().toString();
        return "";
    }

    private NewExpr lowerNew(NewClassTree tree) {
        MethodSymbol constructor = methodSymbol(elementOf(tree), null);
        TypeNode type = tree.getIdentifier() == null ? null : lowerTypeNode(tree.getIdentifier(), constructor);
        TypeDecl body = tree.getClassBody() == null ? null : lowerType(tree.getClassBody());
        return new NewExpr(
                tree.getEnclosingExpression() == null ? null : lowerExpr(tree.getEnclosingExpression()),
                type, mapTypes(tree.getTypeArguments()), mapExprs(tree.getArguments()),
                body, constructor, jtype(typeOf(tree)), range(tree));
    }

    private NewArrayExpr lowerNewArray(NewArrayTree tree) {
        JType type = jtype(typeOf(tree));
        ArrayInitExpr init = tree.getInitializers() == null ? null
                : new ArrayInitExpr(mapExprs(tree.getInitializers()), type, range(tree));
        return new NewArrayExpr(
                tree.getType() == null ? null : lowerTypeNode(tree.getType()),
                mapExprs(tree.getDimensions()), init, type, range(tree));
    }

    private LiteralExpr lowerLiteral(LiteralTree tree) {
        Object value = tree.getValue();
        LiteralExpr.Kind kind = switch (tree.getKind()) {
            case INT_LITERAL -> LiteralExpr.Kind.INT;
            case LONG_LITERAL -> LiteralExpr.Kind.LONG;
            case FLOAT_LITERAL -> LiteralExpr.Kind.FLOAT;
            case DOUBLE_LITERAL -> LiteralExpr.Kind.DOUBLE;
            case CHAR_LITERAL -> LiteralExpr.Kind.CHAR;
            case STRING_LITERAL -> LiteralExpr.Kind.STRING;
            case BOOLEAN_LITERAL -> LiteralExpr.Kind.BOOLEAN;
            case NULL_LITERAL -> LiteralExpr.Kind.NULL;
            default -> value instanceof String ? LiteralExpr.Kind.TEXT_BLOCK : LiteralExpr.Kind.STRING;
        };
        String image = slice(range(tree));
        return new LiteralExpr(kind, value, image, null, range(tree));
    }

    private InstanceOfExpr lowerInstanceOf(InstanceOfTree tree) {
        Pattern pattern = tree.getPattern() == null ? null : lowerPattern(tree.getPattern());
        TypeNode typeNode = tree.getType() == null ? null : lowerTypeNode(tree.getType());
        return new InstanceOfExpr(lowerExpr(tree.getExpression()), typeNode, pattern,
                jtype(typeOf(tree)), range(tree));
    }

    private LambdaExpr lowerLambda(LambdaExpressionTree tree) {
        List<ParamDecl> params = new ArrayList<>();
        for (VariableTree param : tree.getParameters()) params.add(lowerParam(param, false));
        boolean implicit = tree.getParameters().stream().anyMatch(p -> p.getType() == null);
        Expression exprBody = null;
        Block blockBody = null;
        if (tree.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION
                && tree.getBody() instanceof ExpressionTree expr) {
            exprBody = lowerExpr(expr);
        } else if (tree.getBody() instanceof BlockTree block) {
            blockBody = lowerBlock(block);
        } else if (tree.getBody() instanceof StatementTree stmt) {
            blockBody = new Block(List.of(lowerStmt(stmt)), range(tree.getBody()));
        }
        return new LambdaExpr(params, implicit, exprBody, blockBody, jtype(typeOf(tree)), range(tree));
    }

    private MemberRefExpr lowerMemberRef(MemberReferenceTree tree) {
        MemberRefExpr.Mode mode = tree.getMode() == MemberReferenceTree.ReferenceMode.NEW
                ? MemberRefExpr.Mode.NEW : MemberRefExpr.Mode.INVOKE;
        Identifier name = ident(tree.getName().toString(),
                nameRange(tree, tree.getName().toString()), symbolOf(elementOf(tree)));
        Expression expr = null;
        TypeNode type = null;
        ExpressionTree qual = tree.getQualifierExpression();
        if (isTypeNode(qual)) type = lowerTypeNode(qual);
        else expr = lowerExpr(qual);
        return new MemberRefExpr(expr, type, mode, name, mapTypes(tree.getTypeArguments()),
                jtype(typeOf(tree)), range(tree));
    }

    private boolean isTypeNode(Tree tree) {
        if (tree == null) return false;
        return switch (tree.getKind()) {
            case IDENTIFIER, MEMBER_SELECT, PRIMITIVE_TYPE, ARRAY_TYPE, PARAMETERIZED_TYPE,
                    UNION_TYPE, INTERSECTION_TYPE, ANNOTATED_TYPE, UNBOUNDED_WILDCARD,
                    EXTENDS_WILDCARD, SUPER_WILDCARD -> elementOf(tree) instanceof TypeElement
                    || elementOf(tree) instanceof TypeParameterElement
                    || tree.getKind() != Tree.Kind.IDENTIFIER && tree.getKind() != Tree.Kind.MEMBER_SELECT;
            default -> false;
        };
    }

    private Pattern lowerPattern(PatternTree tree) {
        if (tree instanceof BindingPatternTree binding) {
            VariableTree variable = binding.getVariable();
            LocalSymbol symbol = localSymbol(elementOf(variable));
            String name = variable.getName().toString();
            Identifier ident = "_".equals(name) ? null
                    : ident(name, nameRange(variable, name), symbol);
            return new TypePattern(lowerTypeNode(variable.getType()), ident, symbol, range(tree));
        }
        if (tree.getKind().name().contains("RECORD_PATTERN") && tree instanceof JCTree jc) {
            return new RecordPattern(null, List.of(), range(tree));
        }
        if (tree.getKind().name().equals("ANY_PATTERN")) return new UnnamedPattern(range(tree));
        return new UnnamedPattern(range(tree));
    }

    private TypeNode lowerTypeNode(Tree tree) {
        return lowerTypeNode(tree, null);
    }

    /**
     * @param constructorName when non-null (from {@code new}), bind the simple-name Identifier
     *                        of the allocated type to this constructor symbol on first construction
     */
    private TypeNode lowerTypeNode(Tree tree, Symbol constructorName) {
        if (tree == null) return null;
        SourceRange range = range(tree);
        JType resolved = jtype(typeOf(tree));
        return switch (tree) {
            case com.sun.source.tree.PrimitiveTypeTree primitive -> lowerPrimitive(primitive, resolved, range);
            case IdentifierTree ident -> typeName(
                    List.of(ident(ident.getName().toString(), range,
                            lastTypeSymbol(elementOf(ident), constructorName))),
                    resolved, range);
            case MemberSelectTree select -> typeName(typeNameIdents(select, constructorName), resolved, range);
            case com.sun.source.tree.ParameterizedTypeTree parameterized -> {
                TypeNode raw = lowerTypeNode(parameterized.getType(), constructorName);
                List<TypeNode> args = mapTypes(parameterized.getTypeArguments());
                JType type = resolved;
                if (type == JType.ERROR) {
                    JType rawType = raw == null ? JType.ERROR : raw.resolvedType();
                    if (rawType instanceof JType.Declared declared) {
                        List<JType> typeArgs = new ArrayList<>();
                        for (TypeNode arg : args) typeArgs.add(arg.resolvedType());
                        type = new JType.Declared(declared.jvmBinaryName(), typeArgs);
                    } else {
                        type = rawType;
                    }
                }
                yield new ParameterizedTypeNode(raw, args, type, range);
            }
            case com.sun.source.tree.ArrayTypeTree array ->
                    new ArrayTypeNode(lowerTypeNode(array.getType()), resolved, range);
            case WildcardTree wildcard -> lowerWildcard(wildcard, resolved);
            case com.sun.source.tree.UnionTypeTree union ->
                    new UnionTypeNode(mapTypes(union.getTypeAlternatives()), resolved, range);
            case com.sun.source.tree.IntersectionTypeTree intersection ->
                    new IntersectionTypeNode(mapTypes(intersection.getBounds()), resolved, range);
            case com.sun.source.tree.AnnotatedTypeTree annotated -> new ch.castleridge.javals.ast.AnnotatedTypeNode(
                    lowerAnnos(annotated.getAnnotations()), lowerTypeNode(annotated.getUnderlyingType()),
                    resolved, range);
            case ErroneousTree err -> new ErroneousType(lowerErroneous(err), resolved, range);
            default -> {
                if ("var".equals(tree.toString())) yield new VarTypeNode(resolved, range);
                yield typeName(typeNameIdents(tree instanceof ExpressionTree expr ? expr : null, constructorName),
                        resolved, range);
            }
        };
    }

    private TypeName typeName(List<Identifier> names, JType resolved, SourceRange range) {
        return new TypeName(names, resolved, range);
    }

    private WildcardTypeNode lowerWildcard(WildcardTree tree, JType resolved) {
        return switch (tree.getKind()) {
            case EXTENDS_WILDCARD -> new WildcardTypeNode(WildcardTypeNode.BoundKind.EXTENDS,
                    lowerTypeNode(tree.getBound()), resolved, range(tree));
            case SUPER_WILDCARD -> new WildcardTypeNode(WildcardTypeNode.BoundKind.SUPER,
                    lowerTypeNode(tree.getBound()), resolved, range(tree));
            default -> new WildcardTypeNode(WildcardTypeNode.BoundKind.UNBOUNDED, null, resolved, range(tree));
        };
    }

    private Annotation lowerAnnotation(AnnotationTree tree) {
        TypeName name = tree.getAnnotationType() instanceof ExpressionTree expr
                ? typeName(typeNameIdents(expr), jtype(typeOf(tree.getAnnotationType())),
                        range(tree.getAnnotationType()))
                : typeName(List.of(), null, range(tree.getAnnotationType()));
        List<AnnoArg> args = new ArrayList<>();
        for (ExpressionTree arg : tree.getArguments()) {
            if (arg instanceof AssignmentTree assign && assign.getVariable() instanceof IdentifierTree ident) {
                args.add(new AnnoArg(ident(ident.getName().toString(), range(ident), null),
                        lowerExpr(assign.getExpression()), range(arg)));
            } else {
                args.add(new AnnoArg(null, lowerExpr(arg), range(arg)));
            }
        }
        return new Annotation(name, args, jtype(typeOf(tree)), range(tree));
    }

    private List<Annotation> lowerAnnos(List<? extends AnnotationTree> trees) {
        List<Annotation> out = new ArrayList<>();
        for (AnnotationTree tree : trees) out.add(lowerAnnotation(tree));
        return out;
    }

    private List<Annotation> annos(List<? extends AnnotationTree> trees) {
        return lowerAnnos(trees);
    }

    private List<Annotation> annos(ModifiersTree modifiers) {
        return modifiers == null ? List.of() : lowerAnnos(modifiers.getAnnotations());
    }

    private ModuleDecl lowerModule(ModuleTree tree) {
        List<Identifier> names = typeNameIdents(asExpr(tree.getName()));
        List<ModuleDirective> directives = new ArrayList<>();
        for (var dir : tree.getDirectives()) {
            ModuleDirective lowered = lowerDirective(dir);
            if (lowered != null) directives.add(lowered);
        }
        String qualified = join(names);
        ModuleSymbol symbol = new ModuleSymbol(qualified, SymbolKey.of("MD:" + qualified, qualified, ""));
        return new ModuleDecl(tree.getModuleType() == ModuleTree.ModuleKind.OPEN,
                lowerAnnos(tree.getAnnotations()), names, directives, symbol, range(tree));
    }

    private ModuleDirective lowerDirective(Tree tree) {
        return switch (tree) {
            case RequiresTree req -> new RequiresDirective(req.isTransitive(), req.isStatic(),
                    typeNameIdents(asExpr(req.getModuleName())), range(req));
            case ExportsTree exp -> new ExportsDirective(typeNameIdents(asExpr(exp.getPackageName())),
                    moduleTargets(exp.getModuleNames()), range(exp));
            case OpensTree opens -> new OpensDirective(typeNameIdents(asExpr(opens.getPackageName())),
                    moduleTargets(opens.getModuleNames()), range(opens));
            case UsesTree uses -> new UsesDirective(typeName(typeNameIdents(asExpr(uses.getServiceName())),
                    null, range(uses.getServiceName())), range(uses));
            case ProvidesTree provides -> new ProvidesDirective(
                    typeName(typeNameIdents(asExpr(provides.getServiceName())), null,
                            range(provides.getServiceName())),
                    provides.getImplementationNames().stream()
                            .map(n -> typeName(typeNameIdents(asExpr(n)), null, range(n))).toList(),
                    range(provides));
            default -> null;
        };
    }

    private List<List<Identifier>> moduleTargets(List<? extends ExpressionTree> names) {
        if (names == null) return List.of();
        List<List<Identifier>> out = new ArrayList<>();
        for (ExpressionTree name : names) out.add(typeNameIdents(name));
        return out;
    }

    private List<Node> lowerErroneous(ErroneousTree tree) {
        List<Node> out = new ArrayList<>();
        for (Tree err : tree.getErrorTrees()) {
            if (err instanceof ExpressionTree expr) out.add(lowerExpr(expr));
            else if (err instanceof StatementTree stmt) out.add(lowerStmt(stmt));
            else if (err != null) out.add(lowerTypeNode(err));
        }
        return out;
    }

    private List<TypeNode> mapTypes(List<? extends Tree> trees) {
        if (trees == null) return List.of();
        List<TypeNode> out = new ArrayList<>();
        for (Tree tree : trees) out.add(lowerTypeNode(tree));
        return out;
    }

    private List<Expression> mapExprs(List<? extends ExpressionTree> trees) {
        if (trees == null) return List.of();
        List<Expression> out = new ArrayList<>();
        for (ExpressionTree tree : trees) out.add(lowerExpr(tree));
        return out;
    }

    private static ExpressionTree asExpr(Tree tree) {
        return tree instanceof ExpressionTree expr ? expr : null;
    }

    private List<Identifier> typeNameIdents(ExpressionTree tree) {
        return typeNameIdents(tree, null);
    }

    private List<Identifier> typeNameIdents(ExpressionTree tree, Symbol constructorName) {
        List<Identifier> out = new ArrayList<>();
        collectName(tree, out, constructorName);
        return out;
    }

    private void collectName(ExpressionTree tree, List<Identifier> out) {
        collectName(tree, out, null);
    }

    private void collectName(ExpressionTree tree, List<Identifier> out, Symbol constructorName) {
        if (tree instanceof IdentifierTree ident) {
            out.add(ident(ident.getName().toString(), range(ident),
                    lastTypeSymbol(elementOf(ident), constructorName)));
        } else if (tree instanceof MemberSelectTree select) {
            collectName(select.getExpression(), out, null);
            out.add(ident(select.getIdentifier().toString(), selectNameRange(select),
                    lastTypeSymbol(elementOf(select), constructorName)));
        } else if (tree instanceof com.sun.source.tree.ParameterizedTypeTree parameterized
                && parameterized.getType() instanceof ExpressionTree expr) {
            collectName(expr, out, constructorName);
        } else if (tree != null) {
            String image = tree.toString();
            out.add(ident(image, range(tree), lastTypeSymbol(elementOf(tree), constructorName)));
        }
    }

    private Symbol lastTypeSymbol(Element element, Symbol constructorName) {
        return constructorName != null ? constructorName : symbolOf(element);
    }

    private Identifier ident(String name, SourceRange range, Symbol symbol) {
        return new Identifier(name, range, symbol);
    }

    private Identifier label(javax.lang.model.element.Name name) {
        if (name == null) return null;
        return ident(name.toString(), SourceRange.NONE, null);
    }

    private Set<ch.castleridge.javals.ast.Modifier> mods(ModifiersTree tree) {
        if (tree == null) return Set.of();
        EnumSet<ch.castleridge.javals.ast.Modifier> out = EnumSet.noneOf(ch.castleridge.javals.ast.Modifier.class);
        for (Modifier modifier : tree.getFlags()) {
            ch.castleridge.javals.ast.Modifier mapped = switch (modifier) {
                case PUBLIC -> ch.castleridge.javals.ast.Modifier.PUBLIC;
                case PROTECTED -> ch.castleridge.javals.ast.Modifier.PROTECTED;
                case PRIVATE -> ch.castleridge.javals.ast.Modifier.PRIVATE;
                case ABSTRACT -> ch.castleridge.javals.ast.Modifier.ABSTRACT;
                case STATIC -> ch.castleridge.javals.ast.Modifier.STATIC;
                case FINAL -> ch.castleridge.javals.ast.Modifier.FINAL;
                case STRICTFP -> ch.castleridge.javals.ast.Modifier.STRICTFP;
                case TRANSIENT -> ch.castleridge.javals.ast.Modifier.TRANSIENT;
                case VOLATILE -> ch.castleridge.javals.ast.Modifier.VOLATILE;
                case SYNCHRONIZED -> ch.castleridge.javals.ast.Modifier.SYNCHRONIZED;
                case NATIVE -> ch.castleridge.javals.ast.Modifier.NATIVE;
                case DEFAULT -> ch.castleridge.javals.ast.Modifier.DEFAULT;
                case SEALED -> ch.castleridge.javals.ast.Modifier.SEALED;
                case NON_SEALED -> ch.castleridge.javals.ast.Modifier.NON_SEALED;
                default -> null;
            };
            if (mapped != null) out.add(mapped);
        }
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    private SourceRange range(Tree tree) {
        if (tree == null || positions == null) return SourceRange.NONE;
        long start = positions.getStartPosition(cu, tree);
        long end = positions.getEndPosition(cu, tree);
        if (start < 0) return SourceRange.NONE;
        if (end < start) {
            if (tree instanceof IdentifierTree ident) {
                end = start + ident.getName().length();
            } else if (tree instanceof JCTree jc && jc.getPreferredPosition() >= 0) {
                end = jc.getPreferredPosition() + 1;
            } else {
                end = start + 1;
            }
        }
        return new SourceRange((int) start, (int) Math.min(source.length(), end));
    }

    private SourceRange nameRange(Tree tree, String name) {
        SourceRange full = range(tree);
        if (!full.isPresent() || name == null || name.isEmpty()) return full;
        int found = source.indexOf(name, full.start());
        if (found < 0 || found >= full.end()) return new SourceRange(full.start(), Math.min(full.end(), full.start() + name.length()));
        return new SourceRange(found, found + name.length());
    }

    private SourceRange selectNameRange(MemberSelectTree tree) {
        SourceRange full = range(tree);
        String name = tree.getIdentifier().toString();
        if (!full.isPresent()) return SourceRange.NONE;
        int end = full.end();
        int start = Math.max(full.start(), end - name.length());
        return new SourceRange(start, end);
    }

    private String slice(SourceRange range) {
        if (!range.isPresent()) return "";
        int lo = Math.max(0, range.start());
        int hi = Math.min(source.length(), range.end());
        return lo >= hi ? "" : source.substring(lo, hi);
    }

    private Element elementOf(Tree tree) {
        if (tree == null) return null;
        Element fromTree = jcElement(tree);
        if (fromTree != null) return fromTree;
        if (trees == null) return null;
        try {
            TreePath path = trees.getPath(cu, tree);
            return path == null ? null : trees.getElement(path);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private TypeMirror typeOf(Tree tree) {
        if (tree instanceof JCTree jc && jc.type != null) return jc.type;
        if (tree == null || trees == null) return null;
        try {
            TreePath path = trees.getPath(cu, tree);
            return path == null ? null : trees.getTypeMirror(path);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static Element jcElement(Tree tree) {
        return switch (tree) {
            case JCTree.JCIdent ident -> ident.sym;
            case JCTree.JCFieldAccess access -> access.sym;
            case JCTree.JCClassDecl type -> type.sym;
            case JCTree.JCMethodDecl method -> method.sym;
            case JCTree.JCVariableDecl variable -> variable.sym;
            case JCTree.JCNewClass created -> created.constructor;
            case JCTree.JCMemberReference ref -> ref.sym;
            default -> null;
        };
    }

    private Symbol symbolOf(Element element) {
        if (element == null) return null;
        Symbol existing = interned.get(element);
        if (existing != null) return existing;
        if (element instanceof TypeElement type) return typeSymbol(type);
        if (element instanceof ExecutableElement executable) return methodSymbol(executable, null);
        if (element instanceof TypeParameterElement param) return typeVarSymbol(param);
        if (element instanceof PackageElement pkg) {
            PackageSymbol symbol = new PackageSymbol(pkg.getQualifiedName().toString(),
                    SymbolKey.local(pkg.getSimpleName().toString()));
            interned.put(element, symbol);
            return symbol;
        }
        if (element instanceof VariableElement variable) {
            return switch (variable.getKind()) {
                case ENUM_CONSTANT -> enumSymbol(variable, null);
                case FIELD -> fieldSymbol(variable, null);
                case RECORD_COMPONENT -> recordComponentSymbol(variable, null);
                default -> localSymbol(variable);
            };
        }
        return null;
    }

    private TypeSymbol typeSymbol(Element element) {
        if (!(element instanceof TypeElement type)) return null;
        Symbol existing = interned.get(type);
        if (existing instanceof TypeSymbol found) return found;
        TypeDeclKind kind = switch (type.getKind()) {
            case INTERFACE -> TypeDeclKind.INTERFACE;
            case ENUM -> TypeDeclKind.ENUM;
            case ANNOTATION_TYPE -> TypeDeclKind.ANNOTATION;
            case RECORD -> TypeDeclKind.RECORD;
            default -> TypeDeclKind.CLASS;
        };
        String binary = elements == null ? type.getQualifiedName().toString()
                : elements.getBinaryName(type).toString();
        SymbolKey key = astKey(element);
        List<TypeVarSymbol> typeParams = new ArrayList<>();
        TypeSymbol symbol = new TypeSymbol(kind, binary, key, typeParams);
        interned.put(type, symbol);
        for (TypeParameterElement param : type.getTypeParameters()) {
            TypeVarSymbol tv = typeVarSymbol(param);
            if (tv != null) typeParams.add(tv);
        }
        return symbol;
    }

    private MethodSymbol methodSymbol(Element element, TypeSymbol owner) {
        if (!(element instanceof ExecutableElement executable)) return null;
        Symbol existing = interned.get(executable);
        if (existing instanceof MethodSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(executable.getEnclosingElement());
        boolean constructor = executable.getKind() == ElementKind.CONSTRUCTOR;
        String name = constructor ? "<init>" : executable.getSimpleName().toString();
        List<JType> params = new ArrayList<>();
        for (VariableElement param : executable.getParameters()) params.add(jtype(param.asType()));
        boolean synthetic = executable instanceof com.sun.tools.javac.code.Symbol.MethodSymbol ms
                && (ms.flags() & Flags.SYNTHETIC) != 0;
        MethodSymbol symbol = new MethodSymbol(name, jtype(executable.getReturnType()), params,
                resolvedOwner, astKey(executable), constructor, synthetic);
        interned.put(executable, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private FieldSymbol fieldSymbol(Element element, TypeSymbol owner) {
        if (!(element instanceof VariableElement variable)) return null;
        Symbol existing = interned.get(variable);
        if (existing instanceof FieldSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(variable.getEnclosingElement());
        FieldSymbol symbol = new FieldSymbol(variable.getSimpleName().toString(), jtype(variable.asType()),
                resolvedOwner, astKey(variable), false);
        interned.put(variable, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private EnumConstantSymbol enumSymbol(Element element, TypeSymbol owner) {
        if (!(element instanceof VariableElement variable)) return null;
        Symbol existing = interned.get(variable);
        if (existing instanceof EnumConstantSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(variable.getEnclosingElement());
        EnumConstantSymbol symbol = new EnumConstantSymbol(variable.getSimpleName().toString(),
                resolvedOwner, astKey(variable));
        interned.put(variable, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private RecordComponentSymbol recordComponentSymbol(Element element, TypeSymbol owner) {
        if (!(element instanceof VariableElement variable)) return null;
        Symbol existing = interned.get(variable);
        if (existing instanceof RecordComponentSymbol found) return found;
        TypeSymbol resolvedOwner = owner != null ? owner : typeSymbol(variable.getEnclosingElement());
        RecordComponentSymbol symbol = new RecordComponentSymbol(variable.getSimpleName().toString(),
                jtype(variable.asType()), resolvedOwner, astKey(variable), null);
        interned.put(variable, symbol);
        if (resolvedOwner != null) resolvedOwner.addMember(symbol);
        return symbol;
    }

    private LocalSymbol localSymbol(Element element) {
        if (!(element instanceof VariableElement variable)) return null;
        Symbol existing = interned.get(variable);
        if (existing instanceof LocalSymbol found) return found;
        LocalSymbol symbol = new LocalSymbol(variable.getSimpleName().toString(), jtype(variable.asType()));
        interned.put(variable, symbol);
        return symbol;
    }

    private TypeVarSymbol typeVarSymbol(Element element) {
        if (!(element instanceof TypeParameterElement param)) return null;
        Symbol existing = interned.get(param);
        if (existing instanceof TypeVarSymbol found) return found;
        List<JType> bounds = new ArrayList<>();
        for (TypeMirror bound : param.getBounds()) bounds.add(jtype(bound));
        TypeVarSymbol symbol = new TypeVarSymbol(param.getSimpleName().toString(), bounds);
        interned.put(param, symbol);
        return symbol;
    }

    private SymbolKey astKey(Element element) {
        if (elements == null || types == null) {
            return SymbolKey.local(element.getSimpleName().toString());
        }
        return JavacSymbolKeys.of(element, elements, types, trees, index, classpath, sourceJarByBinaryJar)
                .orElseGet(() -> SymbolKey.local(element.getSimpleName().toString()));
    }

    private JType jtype(TypeMirror type) {
        if (type == null) return JType.ERROR;
        return switch (type.getKind()) {
            case BOOLEAN -> JType.Primitive.BOOLEAN;
            case BYTE -> JType.Primitive.BYTE;
            case SHORT -> JType.Primitive.SHORT;
            case INT -> JType.Primitive.INT;
            case LONG -> JType.Primitive.LONG;
            case CHAR -> JType.Primitive.CHAR;
            case FLOAT -> JType.Primitive.FLOAT;
            case DOUBLE -> JType.Primitive.DOUBLE;
            case VOID -> JType.VOID;
            case NULL -> JType.NULL;
            case ARRAY -> JType.array(jtype(((ArrayType) type).getComponentType()));
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                Element element = declared.asElement();
                String binary = element instanceof TypeElement te && elements != null
                        ? elements.getBinaryName(te).toString().replace('.', '/')
                        : element.getSimpleName().toString();
                List<JType> args = new ArrayList<>();
                for (TypeMirror arg : declared.getTypeArguments()) args.add(jtype(arg));
                yield new JType.Declared(binary, args);
            }
            case TYPEVAR -> new JType.TypeVar(((TypeVariable) type).asElement().getSimpleName().toString());
            case WILDCARD -> {
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) {
                    yield new JType.Wildcard(JType.Wildcard.BoundKind.EXTENDS, jtype(wildcard.getExtendsBound()));
                }
                if (wildcard.getSuperBound() != null) {
                    yield new JType.Wildcard(JType.Wildcard.BoundKind.SUPER, jtype(wildcard.getSuperBound()));
                }
                yield JType.Wildcard.unbounded();
            }
            case INTERSECTION -> {
                List<JType> bounds = new ArrayList<>();
                for (TypeMirror bound : ((IntersectionType) type).getBounds()) bounds.add(jtype(bound));
                yield new JType.Intersection(bounds);
            }
            case UNION -> {
                List<JType> alts = new ArrayList<>();
                for (TypeMirror alt : ((UnionType) type).getAlternatives()) alts.add(jtype(alt));
                yield new JType.Union(alts);
            }
            default -> JType.ERROR;
        };
    }

    /**
     * An unattributed or erroneous type is still a {@code PrimitiveTypeTree},
     * but its tag is {@code ERROR}. Asking for the primitive kind asserts.
     */
    private TypeNode lowerPrimitive(com.sun.source.tree.PrimitiveTypeTree primitive,
                                    JType resolved,
                                    SourceRange range) {
        TypeKind kind;
        try {
            kind = primitive.getPrimitiveTypeKind();
        } catch (AssertionError ignored) {
            return new ErroneousType(List.of(), resolved, range);
        }
        if (kind == TypeKind.ERROR || kind == TypeKind.NONE || kind == null) {
            return new ErroneousType(List.of(), resolved, range);
        }
        if (kind == TypeKind.VOID) return new VoidTypeNode(resolved, range);
        return new PrimitiveTypeNode(primitiveKind(kind), resolved, range);
    }

    private JType.Primitive primitiveKind(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> JType.Primitive.BOOLEAN;
            case BYTE -> JType.Primitive.BYTE;
            case SHORT -> JType.Primitive.SHORT;
            case LONG -> JType.Primitive.LONG;
            case CHAR -> JType.Primitive.CHAR;
            case FLOAT -> JType.Primitive.FLOAT;
            case DOUBLE -> JType.Primitive.DOUBLE;
            default -> JType.Primitive.INT;
        };
    }

    private BinaryExpr.Op binaryOp(Tree.Kind kind) {
        return switch (kind) {
            case PLUS -> BinaryExpr.Op.PLUS;
            case MINUS -> BinaryExpr.Op.MINUS;
            case MULTIPLY -> BinaryExpr.Op.MULTIPLY;
            case DIVIDE -> BinaryExpr.Op.DIVIDE;
            case REMAINDER -> BinaryExpr.Op.REMAINDER;
            case AND -> BinaryExpr.Op.AND;
            case OR -> BinaryExpr.Op.OR;
            case XOR -> BinaryExpr.Op.XOR;
            case LEFT_SHIFT -> BinaryExpr.Op.LEFT_SHIFT;
            case RIGHT_SHIFT -> BinaryExpr.Op.RIGHT_SHIFT;
            case UNSIGNED_RIGHT_SHIFT -> BinaryExpr.Op.UNSIGNED_RIGHT_SHIFT;
            case LESS_THAN -> BinaryExpr.Op.LESS;
            case LESS_THAN_EQUAL -> BinaryExpr.Op.LESS_EQUAL;
            case GREATER_THAN -> BinaryExpr.Op.GREATER;
            case GREATER_THAN_EQUAL -> BinaryExpr.Op.GREATER_EQUAL;
            case EQUAL_TO -> BinaryExpr.Op.EQUAL;
            case NOT_EQUAL_TO -> BinaryExpr.Op.NOT_EQUAL;
            case CONDITIONAL_AND -> BinaryExpr.Op.CONDITIONAL_AND;
            case CONDITIONAL_OR -> BinaryExpr.Op.CONDITIONAL_OR;
            default -> BinaryExpr.Op.PLUS;
        };
    }

    private UnaryExpr.Op unaryOp(Tree.Kind kind) {
        return switch (kind) {
            case UNARY_PLUS -> UnaryExpr.Op.PLUS;
            case UNARY_MINUS -> UnaryExpr.Op.MINUS;
            case LOGICAL_COMPLEMENT -> UnaryExpr.Op.NOT;
            case BITWISE_COMPLEMENT -> UnaryExpr.Op.COMPLEMENT;
            case PREFIX_INCREMENT -> UnaryExpr.Op.PRE_INCREMENT;
            case PREFIX_DECREMENT -> UnaryExpr.Op.PRE_DECREMENT;
            case POSTFIX_INCREMENT -> UnaryExpr.Op.POST_INCREMENT;
            case POSTFIX_DECREMENT -> UnaryExpr.Op.POST_DECREMENT;
            default -> UnaryExpr.Op.PLUS;
        };
    }

    private AssignExpr.Op assignOp(Tree.Kind kind) {
        return switch (kind) {
            case PLUS_ASSIGNMENT -> AssignExpr.Op.PLUS_ASSIGN;
            case MINUS_ASSIGNMENT -> AssignExpr.Op.MINUS_ASSIGN;
            case MULTIPLY_ASSIGNMENT -> AssignExpr.Op.MULTIPLY_ASSIGN;
            case DIVIDE_ASSIGNMENT -> AssignExpr.Op.DIVIDE_ASSIGN;
            case REMAINDER_ASSIGNMENT -> AssignExpr.Op.REMAINDER_ASSIGN;
            case AND_ASSIGNMENT -> AssignExpr.Op.AND_ASSIGN;
            case OR_ASSIGNMENT -> AssignExpr.Op.OR_ASSIGN;
            case XOR_ASSIGNMENT -> AssignExpr.Op.XOR_ASSIGN;
            case LEFT_SHIFT_ASSIGNMENT -> AssignExpr.Op.LEFT_SHIFT_ASSIGN;
            case RIGHT_SHIFT_ASSIGNMENT -> AssignExpr.Op.RIGHT_SHIFT_ASSIGN;
            case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT -> AssignExpr.Op.UNSIGNED_RIGHT_SHIFT_ASSIGN;
            default -> AssignExpr.Op.ASSIGN;
        };
    }

    private static String join(List<Identifier> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append('.');
            sb.append(names.get(i).name());
        }
        return sb.toString();
    }
}
