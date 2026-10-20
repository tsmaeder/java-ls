/**
 * Copyright 2026 by Anysphere Inc.
 *
 * Licensed under the MIT License.
 *
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 */
package ch.castleridge.javals.indexing.source.turbine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;

import com.google.turbine.diag.SourceFile;
import com.google.turbine.model.Const;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.TurbineModifier;
import com.google.turbine.tree.TurbineOperatorKind;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.AccessVisibility;
import ch.castleridge.javals.indexing.model.AnnotationRef;
import ch.castleridge.javals.indexing.model.AnnotationValue;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.RecordComponentEntry;
import ch.castleridge.javals.indexing.model.ResourceUris;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeEntry;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;
import ch.castleridge.javals.indexing.source.SourceIndexer;

/**
 * A classpath-free source indexer backed by Turbine's parse-only syntax tree.
 *
 * <p>Turbine deliberately skips method bodies while parsing headers. The
 * identifier bloom filter is therefore built by lexing the original source,
 * so references in method bodies are retained.
 */
public final class TurbineSourceIndexer {

    public static final SourceIndexer INSTANCE = TurbineSourceIndexer::index;

    private TurbineSourceIndexer() {}

    public static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        String source = content.toString();
        Tree.CompUnit unit = Parser.parse(new SourceFile(resourcePath, source));
        indexCompilationUnit(resourcePath, sourceUri, unit, into);

        String resourceUri = ResourceUris.resolve(sourceUri, resourcePath);
        if (resourceUri != null) {
            into.registerBloom(sourceUri, resourcePath,
                    TurbineIdentifierCollector.collectAndBuild(resourcePath, source));
        }
    }

    private static void indexCompilationUnit(
            String resourcePath, String sourceUri, Tree.CompUnit unit, Index into) {
        String packageName = unit.pkg()
                .map(pkg -> joinIdents(pkg.name(), "."))
                .orElse("");
        String packageJvm = packageName.replace('.', '/');

        Map<String, String> singleTypeImports = new HashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        for (Tree.ImportDecl imp : unit.imports()) {
            if (imp.stat()) continue;
            String qualified = joinIdents(imp.type(), ".");
            if (imp.wild()) {
                onDemandImports.add(qualified.replace('.', '/'));
            } else if (!imp.type().isEmpty()) {
                String simple = imp.type().get(imp.type().size() - 1).value();
                singleTypeImports.put(simple, qualifiedToJvm(identValues(imp.type())));
            }
        }

        Set<String> siblings = new LinkedHashSet<>();
        for (Tree.TyDecl declaration : unit.decls()) {
            String simple = declaration.name().value();
            if (!simple.isEmpty() && !isMetadataType(simple)) {
                siblings.add(simple);
            }
        }
        SourceResolutionHints hints = new SourceResolutionHints(
                packageJvm,
                singleTypeImports,
                EmptyArrays.toArray(onDemandImports, EmptyArrays.STRING),
                siblings);

        Deque<String> enclosing = new ArrayDeque<>();
        for (Tree.TyDecl declaration : unit.decls()) {
            indexType(resourcePath, sourceUri, declaration, packageJvm, enclosing,
                    new HashSet<>(), typeModifierFlags(declaration, null), hints, into);
        }
    }

    private static void indexType(
            String resourcePath,
            String sourceUri,
            Tree.TyDecl declaration,
            String packageJvm,
            Deque<String> enclosing,
            Set<String> outerTypeParams,
            int declarationFlags,
            SourceResolutionHints hints,
            Index into) {
        String simple = declaration.name().value();
        if (simple.isEmpty() || isMetadataType(simple)) return;

        String localName = enclosing.isEmpty()
                ? (packageJvm.isEmpty() ? simple : packageJvm + "/" + simple)
                : enclosing.peekLast() + "$" + simple;

        Set<String> classTypeParams = new HashSet<>(outerTypeParams);
        for (Tree.TyParam parameter : declaration.typarams()) {
            classTypeParams.add(parameter.name().value());
        }
        List<TypeParamRef> typeParameters = new ArrayList<>();
        for (Tree.TyParam parameter : declaration.typarams()) {
            typeParameters.add(toTypeParamRef(parameter, classTypeParams, localName));
        }

        Type superType = declaration.xtnds()
                .map(type -> toType(type, classTypeParams, localName))
                .orElse(null);
        List<Type> interfaces = new ArrayList<>();
        for (Tree.ClassTy type : declaration.impls()) {
            interfaces.add(toType(type, classTypeParams, localName));
        }
        List<TypeRef> permitted = new ArrayList<>();
        for (Tree.ClassTy type : declaration.permits()) {
            permitted.add(toClassRef(type, classTypeParams, localName));
        }

        TypeDeclKind kind = declKind(declaration);
        List<RecordComponentEntry> recordComponents = new ArrayList<>();
        Set<String> recordComponentNames = new HashSet<>();
        if (kind == TypeDeclKind.RECORD) {
            for (Tree.VarDecl component : declaration.components()) {
                recordComponents.add(toRecordComponent(component, classTypeParams, localName));
                recordComponentNames.add(component.name().value());
            }
        }

        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();
        List<Tree.TyDecl> nestedTypes = new ArrayList<>();
        List<String> innerNames = new ArrayList<>();
        if (kind == TypeDeclKind.RECORD) {
            for (Tree.VarDecl component : declaration.components()) {
                fields.add(toRecordBackingField(component, classTypeParams, localName));
            }
        }
        for (Tree member : declaration.members()) {
            if (member instanceof Tree.VarDecl field) {
                if (recordComponentNames.contains(field.name().value())) continue;
                int flags = fieldModifierFlags(field, declaration);
                if (!AccessVisibility.shouldIndexMember(flags, field.name().value())) continue;
                fields.add(toFieldEntry(field, flags, classTypeParams, localName));
            } else if (member instanceof Tree.MethDecl method) {
                String name = methodName(method, simple);
                int flags = methodModifierFlags(method, name, declaration);
                if (!AccessVisibility.shouldIndexMember(flags, name)) continue;
                MethodEntry indexed = toMethodEntry(method, name, flags, classTypeParams, localName);
                if (kind == TypeDeclKind.RECORD
                        && isCompactRecordConstructor(method, indexed)
                        && !recordComponents.isEmpty()) {
                    indexed = indexed.withParameters(recordComponentParameters(recordComponents));
                }
                methods.add(indexed);
            } else if (member instanceof Tree.TyDecl nested) {
                if (!AccessVisibility.shouldIndexSourceType(typeModifierFlags(nested, declaration))) continue;
                nestedTypes.add(nested);
                innerNames.add(localName + "$" + nested.name().value());
            }
        }

        TypeEntry entry = new SourceTypeEntry(
                resourcePath,
                sourceUri,
                localName,
                declarationFlags,
                kind,
                superType,
                EmptyArrays.toArray(interfaces, EmptyArrays.TYPE),
                EmptyArrays.toArray(typeParameters, EmptyArrays.TYPE_PARAM),
                EmptyArrays.toArray(fields, EmptyArrays.FIELD),
                EmptyArrays.toArray(methods, EmptyArrays.METHOD),
                EmptyArrays.toArray(innerNames, EmptyArrays.STRING),
                EmptyArrays.toArray(permitted, EmptyArrays.TYPE_REF),
                EmptyArrays.toArray(recordComponents, EmptyArrays.RECORD_COMPONENT),
                annotationsOf(declaration.annos(), localName),
                hints);
        into.add(entry);

        enclosing.addLast(localName);
        try {
            for (Tree.TyDecl nested : nestedTypes) {
                indexType(resourcePath, sourceUri, nested, packageJvm, enclosing,
                        classTypeParams, typeModifierFlags(nested, declaration), hints, into);
            }
        } finally {
            enclosing.removeLast();
        }
    }

    private static FieldEntry toFieldEntry(
            Tree.VarDecl field, int flags, Set<String> typeParams, String ownerJvm) {
        Object constant = null;
        TypeRef constantOwner = null;
        String constantName = null;
        if ((flags & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) {
            constant = field.init().map(TurbineSourceIndexer::literalConstantValue).orElse(null);
            if (constant == null) {
                ConstantFieldRef ref = field.init()
                        .map(init -> constantFieldRef(init, ownerJvm))
                        .orElse(null);
                if (ref != null) {
                    constantOwner = ref.owner;
                    constantName = ref.name;
                }
            }
        }
        return new FieldEntry(
                flags,
                field.name().value(),
                toType(field.ty(), typeParams, ownerJvm),
                constant,
                constantOwner,
                constantName,
                annotationsOf(field.annos(), ownerJvm));
    }

    private static RecordComponentEntry toRecordComponent(
            Tree.VarDecl component, Set<String> typeParams, String ownerJvm) {
        return new RecordComponentEntry(
                component.name().value(),
                toType(component.ty(), typeParams, ownerJvm),
                annotationsOf(component.annos(), ownerJvm));
    }

    private static FieldEntry toRecordBackingField(
            Tree.VarDecl component, Set<String> typeParams, String ownerJvm) {
        int flags = modifierFlags(component.mods()) | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        flags &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
        return new FieldEntry(
                flags,
                component.name().value(),
                toType(component.ty(), typeParams, ownerJvm),
                annotationsOf(component.annos(), ownerJvm));
    }

    private static boolean isCompactRecordConstructor(Tree.MethDecl method, MethodEntry indexed) {
        if (!"<init>".equals(indexed.name())) return false;
        if (method.mods().contains(TurbineModifier.COMPACT_CTOR)) return true;
        return indexed.parameters().length == 0;
    }

    private static ParameterEntry[] recordComponentParameters(List<RecordComponentEntry> components) {
        ParameterEntry[] parameters = new ParameterEntry[components.size()];
        for (int i = 0; i < components.size(); i++) {
            RecordComponentEntry component = components.get(i);
            parameters[i] = new ParameterEntry(
                    component.name(), 0, component.type(), component.annotations());
        }
        return parameters;
    }

    private record ConstantFieldRef(TypeRef owner, String name) {}

    private static ConstantFieldRef constantFieldRef(Tree.Expression expression, String ownerJvm) {
        if (!(expression instanceof Tree.ConstVarName constant)) return null;
        List<String> parts = identValues(constant.name());
        if (parts.isEmpty()) return null;
        String name = parts.remove(parts.size() - 1);
        if (parts.isEmpty()) {
            return new ConstantFieldRef(null, name);
        }
        return new ConstantFieldRef(classRef(parts, ownerJvm), name);
    }

    private static MethodEntry toMethodEntry(
            Tree.MethDecl method,
            String name,
            int flags,
            Set<String> classTypeParams,
            String ownerJvm) {
        Set<String> methodTypeParams = new HashSet<>(classTypeParams);
        for (Tree.TyParam parameter : method.typarams()) {
            methodTypeParams.add(parameter.name().value());
        }

        List<ParameterEntry> parameters = new ArrayList<>();
        for (Tree.VarDecl parameter : method.params()) {
            parameters.add(new ParameterEntry(
                    parameter.name().value(),
                    modifierFlags(parameter.mods()),
                    toType(parameter.ty(), methodTypeParams, ownerJvm),
                    annotationsOf(parameter.annos(), ownerJvm)));
        }

        Type returnType = method.ret()
                .map(type -> toType(type, methodTypeParams, ownerJvm))
                .orElse(Type.Primitive.VOID);
        List<Type> thrownTypes = new ArrayList<>();
        for (Tree.ClassTy thrown : method.exntys()) {
            thrownTypes.add(toType(thrown, methodTypeParams, ownerJvm));
        }
        List<TypeParamRef> typeParameters = new ArrayList<>();
        for (Tree.TyParam parameter : method.typarams()) {
            typeParameters.add(toTypeParamRef(parameter, methodTypeParams, ownerJvm));
        }

        AnnotationValue defaultValue = method.defaultValue()
                .map(value -> toAnnotationValue(value, ownerJvm))
                .orElse(null);
        return new MethodEntry(
                flags,
                name,
                returnType,
                EmptyArrays.toArray(parameters, EmptyArrays.PARAMETER),
                EmptyArrays.toArray(thrownTypes, EmptyArrays.TYPE),
                EmptyArrays.toArray(typeParameters, EmptyArrays.TYPE_PARAM),
                method.mods().contains(TurbineModifier.VARARGS),
                hasBody(name, flags),
                defaultValue,
                annotationsOf(method.annos(), ownerJvm));
    }

    private static boolean hasBody(String name, int flags) {
        if (name.equals("<init>")) return true;
        return (flags & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    private static String methodName(Tree.MethDecl method, String ownerSimpleName) {
        String name = method.name().value();
        return name.equals(ownerSimpleName) ? "<init>" : name;
    }

    private static TypeParamRef toTypeParamRef(
            Tree.TyParam parameter, Set<String> visibleTypeParams, String ownerJvm) {
        if (parameter.bounds().isEmpty()) {
            return TypeParamRef.of(parameter.name().value());
        }
        List<Type> bounds = new ArrayList<>();
        for (Tree bound : parameter.bounds()) {
            bounds.add(toType(bound, visibleTypeParams, ownerJvm));
        }
        return new TypeParamRef(
                parameter.name().value(), EmptyArrays.toArray(bounds, EmptyArrays.TYPE));
    }

    private static Type toType(Tree tree, Set<String> typeParams, String ownerJvm) {
        Type result;
        if (tree instanceof Tree.VoidTy) {
            result = Type.Primitive.VOID;
        } else if (tree instanceof Tree.PrimTy primitive) {
            result = primitiveType(primitive.tykind());
        } else if (tree instanceof Tree.ArrTy array) {
            result = Type.array(toType(array.elem(), typeParams, ownerJvm));
        } else if (tree instanceof Tree.WildTy wildcard) {
            if (wildcard.lower().isPresent()) {
                result = Type.Wildcard.superBound(
                        toType(wildcard.lower().orElseThrow(), typeParams, ownerJvm));
            } else if (wildcard.upper().isPresent()) {
                result = Type.Wildcard.extendsBound(
                        toType(wildcard.upper().orElseThrow(), typeParams, ownerJvm));
            } else {
                result = Type.Wildcard.unbounded();
            }
        } else if (tree instanceof Tree.ClassTy classType) {
            result = classType(classType, typeParams, ownerJvm);
        } else {
            result = TypeRef.resolved("java/lang/Object");
        }

        if (tree instanceof Tree.Type type && !type.annos().isEmpty()) {
            result = Type.Annotated.wrap(
                    result, annotationsOf(type.annos(), ownerJvm));
        }
        return result;
    }

    private static Type classType(
            Tree.ClassTy classType, Set<String> typeParams, String ownerJvm) {
        List<String> parts = identValues(classType.qualifiedName());
        if (parts.size() == 1 && typeParams.contains(parts.get(0))) {
            return Type.typeVariable(parts.get(0));
        }
        TypeRef raw = classRef(parts, ownerJvm);
        if (classType.tyargs().isEmpty()) return raw;

        List<Type> arguments = new ArrayList<>();
        for (Tree.Type argument : classType.tyargs()) {
            arguments.add(toType(argument, typeParams, ownerJvm));
        }
        return Type.parameterized(raw, EmptyArrays.toArray(arguments, EmptyArrays.TYPE));
    }

    private static TypeRef toClassRef(
            Tree.ClassTy classType, Set<String> typeParams, String ownerJvm) {
        Type type = classType(classType, typeParams, ownerJvm);
        if (type instanceof TypeRef ref) return ref;
        if (type instanceof Type.Parameterized parameterized) return parameterized.raw();
        return TypeRef.resolved("java/lang/Object");
    }

    private static TypeRef classRef(List<String> parts, String ownerJvm) {
        if (parts.size() == 1) return TypeRef.unresolved(parts.get(0));
        String jvmName = qualifiedToJvm(parts);
        if (ownerJvm != null) {
            int simpleStart = Math.max(ownerJvm.lastIndexOf('/'), ownerJvm.lastIndexOf('$')) + 1;
            String ownerSimple = ownerJvm.substring(simpleStart);
            if (jvmName.startsWith(ownerSimple + "$")) {
                return TypeRef.resolved(ownerJvm + jvmName.substring(ownerSimple.length()));
            }
        }
        return TypeRef.resolved(jvmName);
    }

    private static Type.Primitive primitiveType(TurbineConstantTypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> Type.Primitive.BOOLEAN;
            case BYTE -> Type.Primitive.BYTE;
            case CHAR -> Type.Primitive.CHAR;
            case SHORT -> Type.Primitive.SHORT;
            case INT -> Type.Primitive.INT;
            case LONG -> Type.Primitive.LONG;
            case FLOAT -> Type.Primitive.FLOAT;
            case DOUBLE -> Type.Primitive.DOUBLE;
            default -> Type.Primitive.INT;
        };
    }

    private static TypeDeclKind declKind(Tree.TyDecl declaration) {
        return switch (declaration.tykind()) {
            case CLASS -> TypeDeclKind.CLASS;
            case INTERFACE -> TypeDeclKind.INTERFACE;
            case ENUM -> TypeDeclKind.ENUM;
            case ANNOTATION -> TypeDeclKind.ANNOTATION;
            case RECORD -> TypeDeclKind.RECORD;
        };
    }

    private static int modifierFlags(Set<TurbineModifier> modifiers) {
        int flags = 0;
        for (TurbineModifier modifier : modifiers) {
            flags |= modifier.flag();
        }
        return flags & 0xffff;
    }

    private static int typeModifierFlags(Tree.TyDecl declaration, Tree.TyDecl owner) {
        int flags = modifierFlags(declaration.mods());
        flags |= switch (declaration.tykind()) {
            case INTERFACE -> Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
            case ANNOTATION -> Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
            case ENUM -> Opcodes.ACC_ENUM;
            case RECORD -> Opcodes.ACC_RECORD;
            case CLASS -> 0;
        };
        if (owner != null
                && (owner.tykind() == com.google.turbine.model.TurbineTyKind.INTERFACE
                        || owner.tykind() == com.google.turbine.model.TurbineTyKind.ANNOTATION)) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        }
        return flags;
    }

    private static int fieldModifierFlags(Tree.VarDecl field, Tree.TyDecl owner) {
        int flags = modifierFlags(field.mods());
        if (owner.tykind() == com.google.turbine.model.TurbineTyKind.INTERFACE
                || owner.tykind() == com.google.turbine.model.TurbineTyKind.ANNOTATION) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
        }
        if (field.mods().contains(TurbineModifier.ACC_ENUM)) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;
        }
        return flags;
    }

    private static int methodModifierFlags(
            Tree.MethDecl method, String name, Tree.TyDecl owner) {
        int flags = modifierFlags(method.mods());
        boolean annotation = owner.tykind() == com.google.turbine.model.TurbineTyKind.ANNOTATION;
        boolean iface = owner.tykind() == com.google.turbine.model.TurbineTyKind.INTERFACE;
        if (annotation) {
            flags |= Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
        } else if (iface) {
            if ((flags & Opcodes.ACC_PRIVATE) == 0) flags |= Opcodes.ACC_PUBLIC;
            if ((flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0
                    && !method.mods().contains(TurbineModifier.DEFAULT)) {
                flags |= Opcodes.ACC_ABSTRACT;
            }
        } else if (owner.tykind() == com.google.turbine.model.TurbineTyKind.ENUM
                && name.equals("<init>")
                && (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0) {
            flags |= Opcodes.ACC_PRIVATE;
        }
        return flags;
    }

    private static AnnotationRef[] annotationsOf(List<Tree.Anno> annotations, String ownerJvm) {
        if (annotations.isEmpty()) return EmptyArrays.ANNOTATION_REF;
        List<AnnotationRef> result = new ArrayList<>();
        for (Tree.Anno annotation : annotations) {
            result.add(toAnnotationRef(annotation, ownerJvm));
        }
        return EmptyArrays.toArray(result, EmptyArrays.ANNOTATION_REF);
    }

    private static AnnotationRef toAnnotationRef(Tree.Anno annotation, String ownerJvm) {
        TypeRef type = classRef(identValues(annotation.name()), ownerJvm);
        Map<String, AnnotationValue> values = new HashMap<>();
        for (Tree.Expression argument : annotation.args()) {
            if (argument instanceof Tree.Assign assignment) {
                values.put(assignment.name().value(),
                        toAnnotationValue(assignment.expr(), ownerJvm));
            } else {
                values.put("value", toAnnotationValue(argument, ownerJvm));
            }
        }
        return new AnnotationRef(type, values);
    }

    private static AnnotationValue toAnnotationValue(Tree tree, String ownerJvm) {
        if (tree instanceof Tree.Literal literal) {
            Object value = constValue(literal.value());
            if (value instanceof String string) return new AnnotationValue.Str(string);
            if (value != null) return new AnnotationValue.Primitive(value);
            return new AnnotationValue.Unsupported("null literal");
        }
        if (tree instanceof Tree.Unary unary) {
            if (unary.expr() instanceof Tree.Literal literal
                    && constValue(literal.value()) instanceof Number number) {
                if (unary.op() == TurbineOperatorKind.NEG) {
                    Object negated = negateNumber(number);
                    if (negated != null) return new AnnotationValue.Primitive(negated);
                } else if (unary.op() == TurbineOperatorKind.UNARY_PLUS) {
                    return new AnnotationValue.Primitive(number);
                }
            }
            return new AnnotationValue.Unsupported("unary expression");
        }
        if (tree instanceof Tree.ArrayInit array) {
            AnnotationValue[] values = new AnnotationValue[array.exprs().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = toAnnotationValue(array.exprs().get(i), ownerJvm);
            }
            return new AnnotationValue.Arr(values);
        }
        if (tree instanceof Tree.AnnoExpr nested) {
            return new AnnotationValue.Nested(toAnnotationRef(nested.value(), ownerJvm));
        }
        if (tree instanceof Tree.ClassLiteral literal) {
            return new AnnotationValue.ClassRef(toType(literal.type(), Set.of(), ownerJvm));
        }
        if (tree instanceof Tree.ConstVarName constant) {
            List<String> parts = identValues(constant.name());
            if (parts.isEmpty()) return new AnnotationValue.Unsupported("empty constant name");
            String constantName = parts.remove(parts.size() - 1);
            TypeRef enumType = parts.isEmpty()
                    ? TypeRef.unresolved("?")
                    : classRef(parts, ownerJvm);
            return new AnnotationValue.EnumConst(enumType, constantName);
        }
        if (tree instanceof Tree.Paren paren) {
            return toAnnotationValue(paren.expr(), ownerJvm);
        }
        return new AnnotationValue.Unsupported("non-constant expression");
    }

    private static Object literalConstantValue(Tree.Expression expression) {
        if (expression instanceof Tree.Literal literal) {
            return constValue(literal.value());
        }
        if (expression instanceof Tree.Unary unary
                && unary.expr() instanceof Tree.Literal literal
                && constValue(literal.value()) instanceof Number number) {
            if (unary.op() == TurbineOperatorKind.NEG) return negateNumber(number);
            if (unary.op() == TurbineOperatorKind.UNARY_PLUS) return number;
        }
        return null;
    }

    private static Object constValue(Const constant) {
        if (!(constant instanceof Const.Value value)) return null;
        Object result = value.getValue();
        if (result instanceof Boolean bool) return bool ? 1 : 0;
        if (result instanceof Character character) return (int) character.charValue();
        if (result instanceof Byte number) return number.intValue();
        if (result instanceof Short number) return number.intValue();
        return result;
    }

    private static Object negateNumber(Number number) {
        if (number instanceof Integer value) return -value;
        if (number instanceof Long value) return -value;
        if (number instanceof Float value) return -value;
        if (number instanceof Double value) return -value;
        if (number instanceof Short value) return (short) -value;
        if (number instanceof Byte value) return (byte) -value;
        return null;
    }

    private static String qualifiedToJvm(List<String> parts) {
        int classStart = parts.size();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                classStart = i;
                break;
            }
        }
        if (classStart == parts.size()) {
            return String.join("/", parts);
        }
        String packageName = String.join("/", parts.subList(0, classStart));
        String typeName = String.join("$", parts.subList(classStart, parts.size()));
        return packageName.isEmpty() ? typeName : packageName + "/" + typeName;
    }

    private static List<String> identValues(List<Tree.Ident> identifiers) {
        List<String> result = new ArrayList<>(identifiers.size());
        for (Tree.Ident identifier : identifiers) {
            result.add(identifier.value());
        }
        return result;
    }

    private static String joinIdents(List<Tree.Ident> identifiers, String delimiter) {
        return String.join(delimiter, identValues(identifiers));
    }

    private static boolean isMetadataType(String simpleName) {
        return simpleName.equals("module-info") || simpleName.equals("package-info");
    }
}
