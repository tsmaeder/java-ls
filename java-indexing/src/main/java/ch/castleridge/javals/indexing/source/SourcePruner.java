package ch.castleridge.javals.indexing.source;

import java.io.IOException;
import java.io.StringWriter;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.Pretty;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

/**
 * Transforms a parsed compilation unit into an API stub: drops private
 * members (except constructors), strips non-constant field initializers,
 * and replaces method bodies with type-appropriate default returns.
 */
public final class SourcePruner {

    private SourcePruner() {}

    public static String prune(CompilationUnitTree cu, Context context) {
        if (!(cu instanceof JCTree.JCCompilationUnit unit)) {
            return cu.toString();
        }
        TreeMaker make = TreeMaker.instance(context);
        Names names = Names.instance(context);
        for (List<JCTree> defs = unit.defs; defs.nonEmpty(); defs = defs.tail) {
            JCTree def = defs.head;
            if (def instanceof JCTree.JCClassDecl clazz) {
                pruneClass(clazz, make, names);
            }
        }
        StringWriter out = new StringWriter();
        try {
            new Pretty(out, true).printExpr(unit);
        } catch (IOException e) {
            return cu.toString();
        }
        return out.toString();
    }

    private static void pruneClass(JCTree.JCClassDecl clazz, TreeMaker make, Names names) {
        ListBuffer<JCTree> kept = new ListBuffer<>();
        for (List<JCTree> members = clazz.defs; members.nonEmpty(); members = members.tail) {
            JCTree member = members.head;
            switch (member.getTag()) {
                case VARDEF -> {
                    JCTree.JCVariableDecl var = (JCTree.JCVariableDecl) member;
                    if (isPrivate(var.mods)) continue;
                    if (!isStaticFinalConstant(var)) {
                        var.init = null;
                    }
                    kept.add(var);
                }
                case METHODDEF -> {
                    JCTree.JCMethodDecl method = (JCTree.JCMethodDecl) member;
                    // Keep private constructors so a type with only private
                    // ctors is not mis-synthesized as having a public default.
                    if (isPrivate(method.mods) && method.name != names.init) continue;
                    stubMethodBody(method, make, names);
                    kept.add(method);
                }
                case CLASSDEF -> {
                    JCTree.JCClassDecl inner = (JCTree.JCClassDecl) member;
                    if (isPrivate(inner.mods)) continue;
                    pruneClass(inner, make, names);
                    kept.add(inner);
                }
                default -> { /* drop static/instance initializers and other non-API members */ }
            }
        }
        clazz.defs = kept.toList();
    }

    private static boolean isPrivate(JCTree.JCModifiers mods) {
        return mods != null && (mods.flags & Flags.PRIVATE) != 0;
    }

    /**
     * A {@code static final} field whose initializer is a compile-time
     * literal (or unary +/- over a numeric literal).
     */
    private static boolean isStaticFinalConstant(JCTree.JCVariableDecl var) {
        if (var.init == null) return false;
        long flags = var.mods == null ? 0 : var.mods.flags;
        if ((flags & (Flags.STATIC | Flags.FINAL)) != (Flags.STATIC | Flags.FINAL)) {
            return false;
        }
        return isLiteralExpression(var.init);
    }

    private static boolean isLiteralExpression(JCTree expr) {
        if (expr == null) return false;
        if (expr.hasTag(JCTree.Tag.LITERAL)) return true;
        // PLUS/MINUS tags are shared by JCUnary (-1) and JCBinary (1 + 2); only
        // unary +/- over a literal qualifies as a compile-time constant here.
        if (expr instanceof JCTree.JCUnary unary) {
            JCTree.Tag tag = unary.getTag();
            if (tag == JCTree.Tag.PLUS || tag == JCTree.Tag.MINUS) {
                return unary.arg != null && unary.arg.hasTag(JCTree.Tag.LITERAL);
            }
        }
        return false;
    }

    private static void stubMethodBody(JCTree.JCMethodDecl method, TreeMaker make, Names names) {
        long flags = method.mods == null ? 0 : method.mods.flags;
        if ((flags & (Flags.ABSTRACT | Flags.NATIVE)) != 0) {
            method.body = null;
            return;
        }
        if (method.body == null) {
            return;
        }
        if (names.init.equals(method.name)) {
            method.body = make.Block(0, List.nil());
            return;
        }
        JCTree.JCStatement stmt = defaultReturn(make, method.restype);
        method.body = stmt == null
                ? make.Block(0, List.nil())
                : make.Block(0, List.of(stmt));
    }

    private static JCTree.JCStatement defaultReturn(TreeMaker make, JCTree retType) {
        if (retType == null) return null;
        if (retType.hasTag(JCTree.Tag.TYPEIDENT)) {
            JCTree.JCPrimitiveTypeTree prim = (JCTree.JCPrimitiveTypeTree) retType;
            if (prim.typetag == TypeTag.VOID) {
                return null;
            }
            return make.Return(literalForPrimitive(make, prim.typetag));
        }
        if (retType.hasTag(JCTree.Tag.IDENT)) {
            String name = retType.toString();
            if ("void".equals(name)) return null;
            if ("boolean".equals(name)) return make.Return(make.Literal(TypeTag.BOOLEAN, 0));
            if ("byte".equals(name)) return make.Return(make.Literal(TypeTag.BYTE, (byte) 0));
            if ("short".equals(name)) return make.Return(make.Literal(TypeTag.SHORT, (short) 0));
            if ("int".equals(name)) return make.Return(make.Literal(TypeTag.INT, 0));
            if ("long".equals(name)) return make.Return(make.Literal(TypeTag.LONG, 0L));
            if ("char".equals(name)) return make.Return(make.Literal(TypeTag.CHAR, 0));
            if ("float".equals(name)) return make.Return(make.Literal(TypeTag.FLOAT, 0.0f));
            if ("double".equals(name)) return make.Return(make.Literal(TypeTag.DOUBLE, 0.0d));
        }
        return make.Return(make.Literal(TypeTag.BOT, null));
    }

    private static JCTree.JCLiteral literalForPrimitive(TreeMaker make, TypeTag tag) {
        return switch (tag) {
            case BOOLEAN -> make.Literal(TypeTag.BOOLEAN, 0);
            case BYTE -> make.Literal(TypeTag.BYTE, (byte) 0);
            case SHORT -> make.Literal(TypeTag.SHORT, (short) 0);
            case INT -> make.Literal(TypeTag.INT, 0);
            case LONG -> make.Literal(TypeTag.LONG, 0L);
            case CHAR -> make.Literal(TypeTag.CHAR, 0);
            case FLOAT -> make.Literal(TypeTag.FLOAT, 0.0f);
            case DOUBLE -> make.Literal(TypeTag.DOUBLE, 0.0d);
            default -> make.Literal(TypeTag.BOT, null);
        };
    }
}
