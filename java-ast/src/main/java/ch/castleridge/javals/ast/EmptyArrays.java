/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.ast;

/**
 * Shared empty arrays for the owned AST. Empty child lists are common; sharing
 * one zero-length array per element type avoids allocating a distinct empty
 * array per node. Callers transfer ownership — never copy after construction.
 */
public final class EmptyArrays {

    public static final Annotation[] ANNOTATION = new Annotation[0];
    public static final AnnoArg[] ANNO_ARG = new AnnoArg[0];
    public static final CaseLabel[] CASE_LABEL = new CaseLabel[0];
    public static final CatchClause[] CATCH_CLAUSE = new CatchClause[0];
    public static final Declaration[] DECLARATION = new Declaration[0];
    public static final Expression[] EXPRESSION = new Expression[0];
    public static final Identifier[] IDENTIFIER = new Identifier[0];
    public static final Identifier[][] IDENTIFIER_TABLE = new Identifier[0][];
    public static final ImportDecl[] IMPORT_DECL = new ImportDecl[0];
    public static final JType[] JTYPE = new JType[0];
    public static final ModuleDirective[] MODULE_DIRECTIVE = new ModuleDirective[0];
    public static final Node[] NODE = new Node[0];
    public static final ParamDecl[] PARAM_DECL = new ParamDecl[0];
    public static final Pattern[] PATTERN = new Pattern[0];
    public static final RecordComponentDecl[] RECORD_COMPONENT = new RecordComponentDecl[0];
    public static final Statement[] STATEMENT = new Statement[0];
    public static final SwitchArm[] SWITCH_ARM = new SwitchArm[0];
    public static final TypeDecl[] TYPE_DECL = new TypeDecl[0];
    public static final TypeName[] TYPE_NAME = new TypeName[0];
    public static final TypeNode[] TYPE_NODE = new TypeNode[0];
    public static final TypeParamDecl[] TYPE_PARAM = new TypeParamDecl[0];
    public static final TypeVarSymbol[] TYPE_VAR_SYMBOL = new TypeVarSymbol[0];
    public static final VarFragment[] VAR_FRAGMENT = new VarFragment[0];

    private EmptyArrays() {}

    /** Returns {@code empty} when {@code src} is null or zero-length; otherwise {@code src}. */
    public static <T> T[] orEmpty(T[] src, T[] empty) {
        if (src == null || src.length == 0) {
            return empty;
        }
        return src;
    }
}
