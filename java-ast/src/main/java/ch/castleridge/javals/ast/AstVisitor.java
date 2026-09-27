/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

import java.util.List;

public abstract class AstVisitor {

    public void visit(Node node) {
        if (node != null) node.accept(this);
    }

    protected final void visitAll(List<? extends Node> nodes) {
        if (nodes == null) return;
        for (Node node : nodes) visit(node);
    }

    public void visitCompilationUnit(CompilationUnit n) {
        visit(n.packageDecl());
        visitAll(n.imports());
        visitAll(n.types());
        visit(n.module());
    }

    public void visitIdentifier(Identifier n) {}

    public void visitPackageDecl(PackageDecl n) {
        visitAll(n.annotations());
        visitAll(n.names());
    }

    public void visitImportDecl(ImportDecl n) {
        visitAll(n.names());
    }

    public void visitTypeDecl(TypeDecl n) {
        visitAll(n.annotations());
        visit(n.name());
        visitAll(n.typeParams());
        visit(n.superclass());
        visitAll(n.interfaces());
        visitAll(n.permits());
        visitAll(n.recordComponents());
        visitAll(n.members());
    }

    public void visitTypeParamDecl(TypeParamDecl n) {
        visitAll(n.annotations());
        visit(n.name());
        visitAll(n.bounds());
    }

    public void visitMethodDecl(MethodDecl n) {
        visitAll(n.annotations());
        visitAll(n.typeParams());
        visit(n.returnType());
        visit(n.name());
        visit(n.receiver());
        visitAll(n.parameters());
        visitAll(n.thrown());
        visit(n.body());
    }

    public void visitConstructorDecl(ConstructorDecl n) {
        visitAll(n.annotations());
        visitAll(n.typeParams());
        visit(n.name());
        visit(n.receiver());
        visitAll(n.parameters());
        visitAll(n.thrown());
        visit(n.body());
    }

    public void visitCompactConstructorDecl(CompactConstructorDecl n) {
        visitAll(n.annotations());
        visit(n.name());
        visit(n.body());
    }

    public void visitFieldDecl(FieldDecl n) {
        visitAll(n.annotations());
        visit(n.type());
        visitAll(n.fragments());
    }

    public void visitVarFragment(VarFragment n) {
        visit(n.name());
        visit(n.initializer());
    }

    public void visitParamDecl(ParamDecl n) {
        visitAll(n.annotations());
        visit(n.type());
        visit(n.name());
    }

    public void visitRecordComponentDecl(RecordComponentDecl n) {
        visitAll(n.annotations());
        visit(n.type());
        visit(n.name());
    }

    public void visitEnumConstantDecl(EnumConstantDecl n) {
        visitAll(n.annotations());
        visit(n.name());
        visitAll(n.arguments());
        visit(n.body());
    }

    public void visitInitializerDecl(InitializerDecl n) {
        visit(n.body());
    }

    public void visitReceiverParam(ReceiverParam n) {
        visitAll(n.annotations());
        visit(n.type());
        visit(n.name());
    }

    public void visitAnnotation(Annotation n) {
        visit(n.name());
        visitAll(n.arguments());
    }

    public void visitAnnoArg(AnnoArg n) {
        visit(n.name());
        visit(n.value());
    }

    public void visitModuleDecl(ModuleDecl n) {
        visitAll(n.annotations());
        visitAll(n.names());
        visitAll(n.directives());
    }

    public void visitRequiresDirective(RequiresDirective n) {
        visitAll(n.moduleName());
    }

    public void visitExportsDirective(ExportsDirective n) {
        visitAll(n.packageName());
        for (List<Identifier> target : n.targets()) visitAll(target);
    }

    public void visitOpensDirective(OpensDirective n) {
        visitAll(n.packageName());
        for (List<Identifier> target : n.targets()) visitAll(target);
    }

    public void visitUsesDirective(UsesDirective n) {
        visit(n.service());
    }

    public void visitProvidesDirective(ProvidesDirective n) {
        visit(n.service());
        visitAll(n.implementations());
    }

    public void visitPrimitiveTypeNode(PrimitiveTypeNode n) {}

    public void visitVoidTypeNode(VoidTypeNode n) {}

    public void visitArrayTypeNode(ArrayTypeNode n) {
        visit(n.element());
    }

    public void visitTypeName(TypeName n) {
        visitAll(n.names());
    }

    public void visitParameterizedTypeNode(ParameterizedTypeNode n) {
        visit(n.raw());
        visitAll(n.typeArguments());
    }

    public void visitWildcardTypeNode(WildcardTypeNode n) {
        visit(n.bound());
    }

    public void visitUnionTypeNode(UnionTypeNode n) {
        visitAll(n.alternatives());
    }

    public void visitIntersectionTypeNode(IntersectionTypeNode n) {
        visitAll(n.bounds());
    }

    public void visitVarTypeNode(VarTypeNode n) {}

    public void visitAnnotatedTypeNode(AnnotatedTypeNode n) {
        visitAll(n.annotations());
        visit(n.inner());
    }

    public void visitErroneousType(ErroneousType n) {
        visitAll(n.fragments());
    }

    public void visitNameExpr(NameExpr n) {
        visit(n.name());
    }

    public void visitSelect(Select n) {
        visit(n.receiver());
        visit(n.name());
    }

    public void visitCallExpr(CallExpr n) {
        visit(n.receiver());
        visit(n.name());
        visitAll(n.typeArguments());
        visitAll(n.arguments());
    }

    public void visitNewExpr(NewExpr n) {
        visit(n.enclosing());
        visit(n.typeNode());
        visitAll(n.typeArguments());
        visitAll(n.arguments());
        visit(n.anonymousBody());
    }

    public void visitNewArrayExpr(NewArrayExpr n) {
        visit(n.elementType());
        visitAll(n.dimensions());
        visit(n.initializer());
    }

    public void visitLiteralExpr(LiteralExpr n) {}

    public void visitThisExpr(ThisExpr n) {
        visit(n.qualifier());
    }

    public void visitSuperExpr(SuperExpr n) {
        visit(n.qualifier());
    }

    public void visitBinaryExpr(BinaryExpr n) {
        visit(n.left());
        visit(n.right());
    }

    public void visitUnaryExpr(UnaryExpr n) {
        visit(n.expression());
    }

    public void visitAssignExpr(AssignExpr n) {
        visit(n.target());
        visit(n.value());
    }

    public void visitConditionalExpr(ConditionalExpr n) {
        visit(n.condition());
        visit(n.thenExpr());
        visit(n.elseExpr());
    }

    public void visitCastExpr(CastExpr n) {
        visit(n.typeNode());
        visit(n.expression());
    }

    public void visitInstanceOfExpr(InstanceOfExpr n) {
        visit(n.expression());
        visit(n.typeNode());
        visit(n.pattern());
    }

    public void visitArrayAccessExpr(ArrayAccessExpr n) {
        visit(n.array());
        visit(n.index());
    }

    public void visitLambdaExpr(LambdaExpr n) {
        visitAll(n.parameters());
        visit(n.expressionBody());
        visit(n.blockBody());
    }

    public void visitMemberRefExpr(MemberRefExpr n) {
        visit(n.qualifierExpr());
        visit(n.qualifierType());
        visit(n.name());
        visitAll(n.typeArguments());
    }

    public void visitSwitchExpr(SwitchExpr n) {
        visit(n.selector());
        visitAll(n.arms());
    }

    public void visitClassLiteralExpr(ClassLiteralExpr n) {
        visit(n.typeNode());
    }

    public void visitParenthesizedExpr(ParenthesizedExpr n) {
        visit(n.expression());
    }

    public void visitArrayInitExpr(ArrayInitExpr n) {
        visitAll(n.elements());
    }

    public void visitErroneousExpr(ErroneousExpr n) {
        visitAll(n.fragments());
    }

    public void visitBlock(Block n) {
        visitAll(n.statements());
    }

    public void visitEmptyStmt(EmptyStmt n) {}

    public void visitExprStmt(ExprStmt n) {
        visit(n.expression());
    }

    public void visitIfStmt(IfStmt n) {
        visit(n.condition());
        visit(n.thenStmt());
        visit(n.elseStmt());
    }

    public void visitWhileStmt(WhileStmt n) {
        visit(n.condition());
        visit(n.body());
    }

    public void visitDoWhileStmt(DoWhileStmt n) {
        visit(n.body());
        visit(n.condition());
    }

    public void visitForStmt(ForStmt n) {
        visitAll(n.init());
        visit(n.condition());
        visitAll(n.update());
        visit(n.body());
    }

    public void visitForEachStmt(ForEachStmt n) {
        visit(n.variable());
        visit(n.iterable());
        visit(n.body());
    }

    public void visitSwitchStmt(SwitchStmt n) {
        visit(n.selector());
        visitAll(n.arms());
    }

    public void visitReturnStmt(ReturnStmt n) {
        visit(n.value());
    }

    public void visitThrowStmt(ThrowStmt n) {
        visit(n.expression());
    }

    public void visitBreakStmt(BreakStmt n) {
        visit(n.label());
    }

    public void visitContinueStmt(ContinueStmt n) {
        visit(n.label());
    }

    public void visitYieldStmt(YieldStmt n) {
        visit(n.value());
    }

    public void visitSynchronizedStmt(SynchronizedStmt n) {
        visit(n.lock());
        visit(n.body());
    }

    public void visitTryStmt(TryStmt n) {
        visitAll(n.resources());
        visit(n.body());
        visitAll(n.catches());
        visit(n.finallyBlock());
    }

    public void visitCatchClause(CatchClause n) {
        visit(n.parameter());
        visit(n.body());
    }

    public void visitAssertStmt(AssertStmt n) {
        visit(n.condition());
        visit(n.message());
    }

    public void visitLabeledStmt(LabeledStmt n) {
        visit(n.label());
        visit(n.statement());
    }

    public void visitLocalDeclStmt(LocalDeclStmt n) {
        visitAll(n.annotations());
        visit(n.type());
        visitAll(n.fragments());
    }

    public void visitLocalTypeStmt(LocalTypeStmt n) {
        visit(n.type());
    }

    public void visitConstructorCallStmt(ConstructorCallStmt n) {
        visit(n.qualifier());
        visitAll(n.typeArguments());
        visitAll(n.arguments());
    }

    public void visitErroneousStmt(ErroneousStmt n) {
        visitAll(n.fragments());
    }

    public void visitSwitchArm(SwitchArm n) {
        visitAll(n.labels());
        visit(n.expressionBody());
        visitAll(n.statements());
    }

    public void visitDefaultLabel(DefaultLabel n) {}

    public void visitConstantLabel(ConstantLabel n) {
        visit(n.value());
    }

    public void visitPatternLabel(PatternLabel n) {
        visit(n.pattern());
        visit(n.guard());
    }

    public void visitTypePattern(TypePattern n) {
        visit(n.type());
        visit(n.name());
    }

    public void visitRecordPattern(RecordPattern n) {
        visit(n.type());
        visitAll(n.nested());
    }

    public void visitUnnamedPattern(UnnamedPattern n) {}
}
