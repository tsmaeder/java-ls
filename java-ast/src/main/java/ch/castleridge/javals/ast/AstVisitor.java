/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

public abstract class AstVisitor {

    public void visit(Node node) {
        if (node != null) node.accept(this);
    }

    protected void visitChildren(Node node) {
        if (node == null) return;
        for (Node child : node.children()) visit(child);
    }

    public void visitCompilationUnit(CompilationUnit n) { visitChildren(n); }
    public void visitIdentifier(Identifier n) { visitChildren(n); }
    public void visitPackageDecl(PackageDecl n) { visitChildren(n); }
    public void visitImportDecl(ImportDecl n) { visitChildren(n); }
    public void visitTypeDecl(TypeDecl n) { visitChildren(n); }
    public void visitTypeParamDecl(TypeParamDecl n) { visitChildren(n); }
    public void visitMethodDecl(MethodDecl n) { visitChildren(n); }
    public void visitConstructorDecl(ConstructorDecl n) { visitChildren(n); }
    public void visitCompactConstructorDecl(CompactConstructorDecl n) { visitChildren(n); }
    public void visitFieldDecl(FieldDecl n) { visitChildren(n); }
    public void visitVarFragment(VarFragment n) { visitChildren(n); }
    public void visitParamDecl(ParamDecl n) { visitChildren(n); }
    public void visitRecordComponentDecl(RecordComponentDecl n) { visitChildren(n); }
    public void visitEnumConstantDecl(EnumConstantDecl n) { visitChildren(n); }
    public void visitInitializerDecl(InitializerDecl n) { visitChildren(n); }
    public void visitReceiverParam(ReceiverParam n) { visitChildren(n); }
    public void visitAnnotation(Annotation n) { visitChildren(n); }
    public void visitAnnoArg(AnnoArg n) { visitChildren(n); }
    public void visitModuleDecl(ModuleDecl n) { visitChildren(n); }
    public void visitRequiresDirective(RequiresDirective n) { visitChildren(n); }
    public void visitExportsDirective(ExportsDirective n) { visitChildren(n); }
    public void visitOpensDirective(OpensDirective n) { visitChildren(n); }
    public void visitUsesDirective(UsesDirective n) { visitChildren(n); }
    public void visitProvidesDirective(ProvidesDirective n) { visitChildren(n); }
    public void visitPrimitiveTypeNode(PrimitiveTypeNode n) { visitChildren(n); }
    public void visitVoidTypeNode(VoidTypeNode n) { visitChildren(n); }
    public void visitArrayTypeNode(ArrayTypeNode n) { visitChildren(n); }
    public void visitTypeName(TypeName n) { visitChildren(n); }
    public void visitParameterizedTypeNode(ParameterizedTypeNode n) { visitChildren(n); }
    public void visitWildcardTypeNode(WildcardTypeNode n) { visitChildren(n); }
    public void visitUnionTypeNode(UnionTypeNode n) { visitChildren(n); }
    public void visitIntersectionTypeNode(IntersectionTypeNode n) { visitChildren(n); }
    public void visitVarTypeNode(VarTypeNode n) { visitChildren(n); }
    public void visitAnnotatedTypeNode(AnnotatedTypeNode n) { visitChildren(n); }
    public void visitErroneousType(ErroneousType n) { visitChildren(n); }
    public void visitNameExpr(NameExpr n) { visitChildren(n); }
    public void visitSelect(Select n) { visitChildren(n); }
    public void visitCallExpr(CallExpr n) { visitChildren(n); }
    public void visitNewExpr(NewExpr n) { visitChildren(n); }
    public void visitNewArrayExpr(NewArrayExpr n) { visitChildren(n); }
    public void visitLiteralExpr(LiteralExpr n) { visitChildren(n); }
    public void visitThisExpr(ThisExpr n) { visitChildren(n); }
    public void visitSuperExpr(SuperExpr n) { visitChildren(n); }
    public void visitBinaryExpr(BinaryExpr n) { visitChildren(n); }
    public void visitUnaryExpr(UnaryExpr n) { visitChildren(n); }
    public void visitAssignExpr(AssignExpr n) { visitChildren(n); }
    public void visitConditionalExpr(ConditionalExpr n) { visitChildren(n); }
    public void visitCastExpr(CastExpr n) { visitChildren(n); }
    public void visitInstanceOfExpr(InstanceOfExpr n) { visitChildren(n); }
    public void visitArrayAccessExpr(ArrayAccessExpr n) { visitChildren(n); }
    public void visitLambdaExpr(LambdaExpr n) { visitChildren(n); }
    public void visitMemberRefExpr(MemberRefExpr n) { visitChildren(n); }
    public void visitSwitchExpr(SwitchExpr n) { visitChildren(n); }
    public void visitClassLiteralExpr(ClassLiteralExpr n) { visitChildren(n); }
    public void visitParenthesizedExpr(ParenthesizedExpr n) { visitChildren(n); }
    public void visitArrayInitExpr(ArrayInitExpr n) { visitChildren(n); }
    public void visitErroneousExpr(ErroneousExpr n) { visitChildren(n); }
    public void visitBlock(Block n) { visitChildren(n); }
    public void visitEmptyStmt(EmptyStmt n) { visitChildren(n); }
    public void visitExprStmt(ExprStmt n) { visitChildren(n); }
    public void visitIfStmt(IfStmt n) { visitChildren(n); }
    public void visitWhileStmt(WhileStmt n) { visitChildren(n); }
    public void visitDoWhileStmt(DoWhileStmt n) { visitChildren(n); }
    public void visitForStmt(ForStmt n) { visitChildren(n); }
    public void visitForEachStmt(ForEachStmt n) { visitChildren(n); }
    public void visitSwitchStmt(SwitchStmt n) { visitChildren(n); }
    public void visitReturnStmt(ReturnStmt n) { visitChildren(n); }
    public void visitThrowStmt(ThrowStmt n) { visitChildren(n); }
    public void visitBreakStmt(BreakStmt n) { visitChildren(n); }
    public void visitContinueStmt(ContinueStmt n) { visitChildren(n); }
    public void visitYieldStmt(YieldStmt n) { visitChildren(n); }
    public void visitSynchronizedStmt(SynchronizedStmt n) { visitChildren(n); }
    public void visitTryStmt(TryStmt n) { visitChildren(n); }
    public void visitCatchClause(CatchClause n) { visitChildren(n); }
    public void visitAssertStmt(AssertStmt n) { visitChildren(n); }
    public void visitLabeledStmt(LabeledStmt n) { visitChildren(n); }
    public void visitLocalDeclStmt(LocalDeclStmt n) { visitChildren(n); }
    public void visitLocalTypeStmt(LocalTypeStmt n) { visitChildren(n); }
    public void visitConstructorCallStmt(ConstructorCallStmt n) { visitChildren(n); }
    public void visitErroneousStmt(ErroneousStmt n) { visitChildren(n); }
    public void visitSwitchArm(SwitchArm n) { visitChildren(n); }
    public void visitDefaultLabel(DefaultLabel n) { visitChildren(n); }
    public void visitConstantLabel(ConstantLabel n) { visitChildren(n); }
    public void visitPatternLabel(PatternLabel n) { visitChildren(n); }
    public void visitTypePattern(TypePattern n) { visitChildren(n); }
    public void visitRecordPattern(RecordPattern n) { visitChildren(n); }
    public void visitUnnamedPattern(UnnamedPattern n) { visitChildren(n); }
}
