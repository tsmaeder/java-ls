/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import ch.castleridge.javals.ast.EnumConstantSymbol;
import ch.castleridge.javals.ast.FieldSymbol;
import ch.castleridge.javals.ast.JType;
import ch.castleridge.javals.ast.LocalSymbol;
import ch.castleridge.javals.ast.MethodSymbol;
import ch.castleridge.javals.ast.ModuleSymbol;
import ch.castleridge.javals.ast.PackageSymbol;
import ch.castleridge.javals.ast.RecordComponentSymbol;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.TypeVarSymbol;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Human-readable declaration signatures for hover and completion detail.
 */
public final class AstSignatures {

    private AstSignatures() {}

    public static String of(Symbol symbol) {
        if (symbol == null) return "";
        return switch (symbol) {
            case MethodSymbol method -> method(method);
            case FieldSymbol field -> typedName(field.type(), field.name());
            case LocalSymbol local -> typedName(local.type(), local.name());
            case EnumConstantSymbol constant -> typedName(constant.type(), constant.name());
            case RecordComponentSymbol component -> typedName(component.type(), component.name());
            case TypeSymbol type -> type.jvmBinaryName().replace('/', '.').replace('$', '.');
            case TypeVarSymbol typeVar -> typeVar.name();
            case PackageSymbol pkg -> "package " + pkg.qualifiedName();
            case ModuleSymbol module -> "module " + module.name();
        };
    }

    public static String method(MethodSymbol symbol) {
        if (symbol == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!symbol.constructor()) sb.append(symbol.returnType()).append(' ');
        sb.append(symbol.name()).append('(');
        JType[] params = symbol.parameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i]);
        }
        return sb.append(')').toString();
    }

    public static String method(MethodEntry method) {
        if (method == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!"<init>".equals(method.name())) sb.append(typeString(method.returnType())).append(' ');
        sb.append(method.name()).append('(');
        Type[] params = method.paramTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeString(params[i]));
        }
        return sb.append(')').toString();
    }

    public static String typeString(Type type) {
        if (type == null) return "";
        Type plain = type instanceof Type.Annotated annotated ? annotated.unwrap() : type;
        return switch (plain) {
            case Type.Primitive primitive -> primitive == Type.Primitive.VOID
                    ? "void" : primitive.name().toLowerCase();
            case Type.Array array -> typeString(array.element()) + "[]";
            case Type.TypeVariable variable -> variable.name();
            case Type.Wildcard wild -> switch (wild.kind()) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + typeString(wild.bound());
                case SUPER -> "? super " + typeString(wild.bound());
            };
            case Type.Parameterized parameterized -> {
                StringBuilder sb = new StringBuilder(typeString(parameterized.raw())).append('<');
                Type[] args = parameterized.typeArgs();
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeString(args[i]));
                }
                yield sb.append('>').toString();
            }
            case TypeRef.Resolved resolved ->
                    resolved.jvmBinaryName().replace('/', '.').replace('$', '.');
            case TypeRef.Unresolved unresolved -> unresolved.simpleName();
            default -> plain.toString();
        };
    }

    private static String typedName(JType type, String name) {
        String typeText = type == null ? "" : type.toString();
        if (typeText.isEmpty()) return name == null ? "" : name;
        return typeText + ' ' + (name == null ? "" : name);
    }
}
