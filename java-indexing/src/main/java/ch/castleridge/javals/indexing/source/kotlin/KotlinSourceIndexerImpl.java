/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.treesitter.TsNode;
import ch.castleridge.javals.indexing.model.AccessVisibility;
import ch.castleridge.javals.indexing.model.EmptyArrays;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.ParameterEntry;
import ch.castleridge.javals.indexing.model.SourceResolutionHints;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeDeclKind;
import ch.castleridge.javals.indexing.model.TypeParamRef;
import ch.castleridge.javals.indexing.model.TypeRef;

/**
 * Walks a Kotlin tree-sitter CST and emits {@link SourceTypeEntry}
 * declarations plus an identifier bloom filter.
 */
final class KotlinSourceIndexerImpl {

    private KotlinSourceIndexerImpl() {}

    static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        if (resourcePath == null || sourceUri == null || content == null || into == null) {
            return;
        }
        String source = content.toString();
        try {
            Optional<TsNode> parsed = KotlinTreeSitter.parse(sourceUri, source);
            if (parsed.isEmpty()) {
                System.err.println("Failed to parse Kotlin source " + resourcePath);
                return;
            }
            TsNode root = parsed.get();
            indexFile(resourcePath, sourceUri, root, into);
            try {
                into.registerBloom(sourceUri, resourcePath,
                        KotlinIdentifierCollector.collectAndBuild(root));
            } catch (RuntimeException | StackOverflowError e) {
                System.err.println("Failed to build identifier bloom filter for " + resourcePath
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } catch (RuntimeException | Error e) {
            System.err.println("Failed to index Kotlin source " + resourcePath
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void indexFile(String resourcePath, String sourceUri, TsNode root, Index into) {
        String packageJvm = "";
        Map<String, String> singleTypeImports = new HashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        List<TsNode> topTypes = new ArrayList<>();
        List<TsNode> topCallables = new ArrayList<>();
        String facadeFromJvmName = null;

        for (TsNode child : namedChildren(root)) {
            switch (child.type()) {
                case "package_header" -> packageJvm = identifierToJvm(childNamed(child, "identifier"));
                case "import_list" -> {
                    for (TsNode imp : namedChildren(child)) {
                        if ("import_header".equals(imp.type())) {
                            collectImport(imp, singleTypeImports, onDemandImports);
                        }
                    }
                }
                case "import_header" -> collectImport(child, singleTypeImports, onDemandImports);
                case "file_annotation" -> {
                    String jvmName = parseFileJvmName(child);
                    if (jvmName != null) facadeFromJvmName = jvmName;
                }
                case "class_declaration", "object_declaration" -> topTypes.add(child);
                case "function_declaration", "property_declaration" -> topCallables.add(child);
                default -> {
                }
            }
        }

        Set<String> siblings = new LinkedHashSet<>();
        for (TsNode typeNode : topTypes) {
            String name = typeSimpleName(typeNode);
            if (name != null && !name.isEmpty()) siblings.add(name);
        }
        String fileName = simpleName(resourcePath);
        String facadeSimple = facadeFromJvmName != null ? facadeFromJvmName : defaultFileFacadeName(fileName);
        boolean hasTopLevelMembers = !topCallables.isEmpty();
        if (hasTopLevelMembers) {
            siblings.add(facadeSimple);
        }

        SourceResolutionHints hints = new SourceResolutionHints(
                packageJvm,
                singleTypeImports,
                EmptyArrays.toArray(onDemandImports, EmptyArrays.STRING),
                siblings);

        Deque<String> enclosing = new ArrayDeque<>();
        for (TsNode typeNode : topTypes) {
            indexType(resourcePath, sourceUri, typeNode, packageJvm, enclosing,
                    new HashSet<>(), hints, into, false);
        }

        if (hasTopLevelMembers) {
            indexFileFacade(resourcePath, sourceUri, topCallables, packageJvm, facadeSimple, hints, into);
        }
    }

    private static void collectImport(TsNode importHeader, Map<String, String> single,
                                      List<String> onDemand) {
        TsNode id = childNamed(importHeader, "identifier");
        String jvm = identifierToJvm(id);
        if (jvm == null || jvm.isEmpty()) return;
        if (childNamed(importHeader, "wildcard_import") != null) {
            onDemand.add(jvm);
            return;
        }
        TsNode alias = childNamed(importHeader, "import_alias");
        String simple;
        if (alias != null) {
            TsNode aliasId = firstNamedOfTypes(alias, "type_identifier", "simple_identifier");
            simple = text(aliasId);
        } else {
            int slash = jvm.lastIndexOf('/');
            simple = slash < 0 ? jvm : jvm.substring(slash + 1);
        }
        if (simple != null && !simple.isEmpty()) {
            single.put(simple, jvm);
        }
    }

    private static void indexFileFacade(String resourcePath, String sourceUri, List<TsNode> callables,
                                        String packageJvm, String facadeSimple,
                                        SourceResolutionHints hints, Index into) {
        String jvmOwner = packageJvm.isEmpty() ? facadeSimple : packageJvm + "/" + facadeSimple;
        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();

        for (TsNode decl : callables) {
            if ("property_declaration".equals(decl.type())) {
                FieldEntry field = toFieldEntry(decl, Set.of(), jvmOwner, true);
                if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                    fields.add(field);
                }
                MethodEntry getter = syntheticPropertyAccessor(decl, true, true, Set.of(), jvmOwner);
                if (getter != null) methods.add(getter);
                if (isVarProperty(decl)) {
                    MethodEntry setter = syntheticPropertyAccessor(decl, false, true, Set.of(), jvmOwner);
                    if (setter != null) methods.add(setter);
                }
            } else if ("function_declaration".equals(decl.type())) {
                MethodEntry method = toMethodEntry(decl, Set.of(), jvmOwner, true);
                if (method != null && AccessVisibility.shouldIndexMember(method.modifiers(), method.name())) {
                    methods.add(method);
                }
            }
        }

        into.add(new SourceTypeEntry(
                resourcePath,
                sourceUri,
                jvmOwner,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                TypeDeclKind.CLASS,
                TypeRef.resolved("java/lang/Object"),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                EmptyArrays.toArray(fields, EmptyArrays.FIELD),
                EmptyArrays.toArray(methods, EmptyArrays.METHOD),
                EmptyArrays.STRING,
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                hints));
    }

    private static void indexType(String resourcePath, String sourceUri, TsNode typeNode,
                                  String packageJvm, Deque<String> enclosing,
                                  Set<String> outerTypeParams, SourceResolutionHints hints,
                                  Index into, boolean nested) {
        boolean isObject = "object_declaration".equals(typeNode.type())
                || "companion_object".equals(typeNode.type());
        boolean isCompanion = "companion_object".equals(typeNode.type());

        String simple = typeSimpleName(typeNode);
        if ((simple == null || simple.isEmpty()) && isCompanion) {
            simple = "Companion";
        }
        if (simple == null || simple.isEmpty()) {
            return;
        }

        String localName;
        if (enclosing.isEmpty()) {
            localName = packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
        } else {
            localName = enclosing.peekLast() + "$" + simple;
        }

        int vis = visibilityFlags(typeNode);
        if (!AccessVisibility.shouldIndexSourceType(vis)) {
            return;
        }

        Set<String> typeParams = new HashSet<>(outerTypeParams);
        List<TypeParamRef> declaredTypeParams = new ArrayList<>();
        TsNode typeParamsNode = childNamed(typeNode, "type_parameters");
        if (typeParamsNode != null) {
            for (TsNode tp : namedChildren(typeParamsNode)) {
                if (!"type_parameter".equals(tp.type())) continue;
                String tpName = text(childNamed(tp, "type_identifier"));
                if (tpName != null) typeParams.add(tpName);
            }
            for (TsNode tp : namedChildren(typeParamsNode)) {
                if (!"type_parameter".equals(tp.type())) continue;
                declaredTypeParams.add(toTypeParamRef(tp, typeParams, localName));
            }
        }

        TypeDeclKind declKind = declKind(typeNode, isObject);
        int modifiers = typeModifiers(typeNode, declKind, isObject, nested);

        Type superRef = null;
        List<Type> interfaceRefs = new ArrayList<>();
        for (TsNode del : namedChildren(typeNode)) {
            if (!"delegation_specifier".equals(del.type())) continue;
            Type t = delegationType(del, typeParams, localName);
            if (t == null) continue;
            if (declKind == TypeDeclKind.INTERFACE || declKind == TypeDeclKind.ANNOTATION) {
                interfaceRefs.add(t);
            } else if (superRef == null && !isObject) {
                superRef = t;
            } else {
                interfaceRefs.add(t);
            }
        }

        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();
        List<String> innerTypes = new ArrayList<>();
        List<TsNode> nestedTypes = new ArrayList<>();

        TsNode primary = childNamed(typeNode, "primary_constructor");
        if (primary != null) {
            MethodEntry ctor = toConstructorEntry(primary, typeParams, localName);
            if (ctor != null && AccessVisibility.shouldIndexMember(ctor.modifiers(), ctor.name())) {
                methods.add(ctor);
            }
            for (TsNode param : namedChildren(primary)) {
                if (!"class_parameter".equals(param.type())) continue;
                if (childNamed(param, "binding_pattern_kind") == null) continue;
                FieldEntry field = constructorPropertyField(param, typeParams, localName);
                if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                    fields.add(field);
                }
            }
        } else if (declKind != TypeDeclKind.INTERFACE && declKind != TypeDeclKind.ANNOTATION && !isObject) {
            methods.add(new MethodEntry(
                    Opcodes.ACC_PUBLIC, "<init>", Type.Primitive.VOID,
                    EmptyArrays.TYPE, EmptyArrays.TYPE, EmptyArrays.ANNOTATION_REF));
        }

        if (isObject) {
            fields.add(new FieldEntry(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "INSTANCE",
                    TypeRef.resolved(localName),
                    EmptyArrays.ANNOTATION_REF));
        }

        TsNode body = firstNamedOfTypes(typeNode, "class_body", "enum_class_body");
        if (body != null) {
            collectBodyMembers(body, typeParams, localName, fields, methods, nestedTypes, innerTypes);
        }

        into.add(new SourceTypeEntry(
                resourcePath,
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
                EmptyArrays.TYPE_REF,
                EmptyArrays.RECORD_COMPONENT,
                EmptyArrays.ANNOTATION_REF,
                hints));

        enclosing.addLast(localName);
        try {
            for (TsNode child : nestedTypes) {
                indexType(resourcePath, sourceUri, child, packageJvm, enclosing,
                        typeParams, hints, into, true);
            }
        } finally {
            enclosing.removeLast();
        }
    }

    private static void collectBodyMembers(TsNode body, Set<String> typeParams, String localName,
                                           List<FieldEntry> fields, List<MethodEntry> methods,
                                           List<TsNode> nestedTypes, List<String> innerTypes) {
        for (TsNode member : namedChildren(body)) {
            switch (member.type()) {
                case "enum_entry" -> {
                    FieldEntry field = enumConstantField(member, localName);
                    if (field != null) fields.add(field);
                }
                case "property_declaration" -> {
                    FieldEntry field = toFieldEntry(member, typeParams, localName, false);
                    if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                        fields.add(field);
                    }
                    MethodEntry getter = syntheticPropertyAccessor(member, true, false, typeParams, localName);
                    if (getter != null) methods.add(getter);
                    if (isVarProperty(member)) {
                        MethodEntry setter = syntheticPropertyAccessor(member, false, false, typeParams, localName);
                        if (setter != null) methods.add(setter);
                    }
                }
                case "function_declaration" -> {
                    MethodEntry method = toMethodEntry(member, typeParams, localName, false);
                    if (method != null && AccessVisibility.shouldIndexMember(method.modifiers(), method.name())) {
                        methods.add(method);
                    }
                }
                case "secondary_constructor" -> {
                    MethodEntry ctor = toConstructorEntry(member, typeParams, localName);
                    if (ctor != null && AccessVisibility.shouldIndexMember(ctor.modifiers(), ctor.name())) {
                        methods.add(ctor);
                    }
                }
                case "class_declaration", "object_declaration", "companion_object" -> {
                    if (!AccessVisibility.shouldIndexSourceType(visibilityFlags(member))) {
                        break;
                    }
                    String nestedName = typeSimpleName(member);
                    if ("companion_object".equals(member.type())
                            && (nestedName == null || nestedName.isEmpty())) {
                        nestedName = "Companion";
                    }
                    if (nestedName == null || nestedName.isEmpty()) break;
                    nestedTypes.add(member);
                    innerTypes.add(localName + "$" + nestedName);
                }
                default -> {
                }
            }
        }
    }

    private static TypeDeclKind declKind(TsNode typeNode, boolean isObject) {
        if (isObject) return TypeDeclKind.CLASS;
        if (childNamed(typeNode, "enum_class_body") != null) return TypeDeclKind.ENUM;
        if (headerContainsKeyword(typeNode, "interface")) return TypeDeclKind.INTERFACE;
        if (headerContainsKeyword(typeNode, "enum")) return TypeDeclKind.ENUM;
        if (modifierContains(typeNode, "class_modifier", "annotation")) return TypeDeclKind.ANNOTATION;
        return TypeDeclKind.CLASS;
    }

    private static int typeModifiers(TsNode typeNode, TypeDeclKind kind, boolean isObject, boolean nested) {
        int flags = visibilityFlags(typeNode);
        if (kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION) {
            flags |= Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;
            if (kind == TypeDeclKind.ANNOTATION) {
                flags |= Opcodes.ACC_ANNOTATION;
            }
        } else if (kind == TypeDeclKind.ENUM) {
            flags |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        } else {
            flags |= Opcodes.ACC_SUPER;
            if (modifierContains(typeNode, "inheritance_modifier", "abstract")
                    || modifierContains(typeNode, "class_modifier", "sealed")
                    || modifierContains(typeNode, "inheritance_modifier", "sealed")) {
                flags |= Opcodes.ACC_ABSTRACT;
            } else if (!modifierContains(typeNode, "inheritance_modifier", "open")) {
                flags |= Opcodes.ACC_FINAL;
            }
        }
        if (isObject) {
            flags |= Opcodes.ACC_FINAL;
        }
        if (nested && !modifierContains(typeNode, "class_modifier", "inner")) {
            flags |= Opcodes.ACC_STATIC;
        }
        return flags;
    }

    private static int visibilityFlags(TsNode owner) {
        TsNode modifiers = childNamed(owner, "modifiers");
        if (modifiers == null) return Opcodes.ACC_PUBLIC;
        for (TsNode child : namedChildren(modifiers)) {
            if (!"visibility_modifier".equals(child.type())) continue;
            return switch (text(child)) {
                case "private" -> Opcodes.ACC_PRIVATE;
                case "protected" -> Opcodes.ACC_PROTECTED;
                case "internal" -> 0;
                default -> Opcodes.ACC_PUBLIC;
            };
        }
        return Opcodes.ACC_PUBLIC;
    }

    private static boolean modifierContains(TsNode owner, String modifierType, String text) {
        TsNode modifiers = childNamed(owner, "modifiers");
        if (modifiers == null) return false;
        for (TsNode child : namedChildren(modifiers)) {
            if (modifierType.equals(child.type()) && text.equals(text(child))) {
                return true;
            }
        }
        return false;
    }

    private static FieldEntry toFieldEntry(TsNode prop, Set<String> typeParams,
                                           String ownerJvm, boolean topLevel) {
        String name = propertyName(prop);
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(prop);
        if (topLevel) {
            flags |= Opcodes.ACC_STATIC;
        }
        boolean isVar = isVarProperty(prop);
        boolean isConst = modifierContains(prop, "property_modifier", "const");
        if (!isVar || isConst) {
            flags |= Opcodes.ACC_FINAL;
        }
        if (isConst) {
            flags |= Opcodes.ACC_STATIC;
        }
        Type type = propertyType(prop, typeParams, ownerJvm);
        if (type == null) type = TypeRef.unresolved("Any");
        return new FieldEntry(flags, name, type, EmptyArrays.ANNOTATION_REF);
    }

    private static FieldEntry constructorPropertyField(TsNode param, Set<String> typeParams,
                                                       String ownerJvm) {
        String name = text(childNamed(param, "simple_identifier"));
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(param);
        TsNode binding = childNamed(param, "binding_pattern_kind");
        if (binding == null || "val".equals(text(binding))) {
            flags |= Opcodes.ACC_FINAL;
        }
        Type type = toTypeNode(typeChild(param), typeParams, ownerJvm);
        if (type == null) type = TypeRef.unresolved("Any");
        return new FieldEntry(flags, name, type, EmptyArrays.ANNOTATION_REF);
    }

    private static FieldEntry enumConstantField(TsNode entry, String ownerJvm) {
        String name = text(childNamed(entry, "simple_identifier"));
        if (name == null || name.isEmpty()) return null;
        return new FieldEntry(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                name,
                TypeRef.resolved(ownerJvm),
                EmptyArrays.ANNOTATION_REF);
    }

    private static MethodEntry syntheticPropertyAccessor(TsNode prop, boolean getter,
                                                         boolean topLevel,
                                                         Set<String> typeParams, String ownerJvm) {
        String name = propertyName(prop);
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(prop);
        if (AccessVisibility.isPrivate(flags)) return null;
        if (topLevel) flags |= Opcodes.ACC_STATIC;
        Type propType = propertyType(prop, typeParams, ownerJvm);
        if (propType == null) propType = TypeRef.unresolved("Any");
        if (getter) {
            String methodName = "get" + capitalize(name);
            if (propType == Type.Primitive.BOOLEAN || isBooleanTypeName(propType)) {
                methodName = "is" + capitalize(name);
                if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                    methodName = name;
                }
            }
            return new MethodEntry(flags, methodName, propType,
                    EmptyArrays.TYPE, EmptyArrays.TYPE, EmptyArrays.ANNOTATION_REF);
        }
        String methodName = "set" + capitalize(name);
        return new MethodEntry(flags, methodName, Type.Primitive.VOID,
                new Type[]{propType}, EmptyArrays.TYPE, EmptyArrays.ANNOTATION_REF);
    }

    private static boolean isBooleanTypeName(Type type) {
        if (type instanceof TypeRef.Unresolved u) {
            return "Boolean".equals(u.simpleName()) || "boolean".equals(u.simpleName());
        }
        return false;
    }

    private static MethodEntry toMethodEntry(TsNode fn, Set<String> typeParams,
                                             String ownerJvm, boolean topLevel) {
        String name = text(childNamed(fn, "simple_identifier"));
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(fn);
        if (topLevel) flags |= Opcodes.ACC_STATIC;
        if (modifierContains(fn, "inheritance_modifier", "abstract")) flags |= Opcodes.ACC_ABSTRACT;
        if (modifierContains(fn, "inheritance_modifier", "final")) flags |= Opcodes.ACC_FINAL;
        if (modifierContains(fn, "inheritance_modifier", "open")) {
            flags &= ~Opcodes.ACC_FINAL;
        }

        Set<String> methodTypeParams = new HashSet<>(typeParams);
        List<TypeParamRef> typeParamRefs = new ArrayList<>();
        TsNode typeParamsNode = childNamed(fn, "type_parameters");
        if (typeParamsNode != null) {
            for (TsNode tp : namedChildren(typeParamsNode)) {
                if (!"type_parameter".equals(tp.type())) continue;
                String tpName = text(childNamed(tp, "type_identifier"));
                if (tpName != null) methodTypeParams.add(tpName);
            }
            for (TsNode tp : namedChildren(typeParamsNode)) {
                if (!"type_parameter".equals(tp.type())) continue;
                typeParamRefs.add(toTypeParamRef(tp, methodTypeParams, ownerJvm));
            }
        }

        Type returnType = toTypeNode(typeChild(fn), methodTypeParams, ownerJvm);
        if (returnType == null) {
            returnType = childNamed(fn, "function_body") != null && hasBlockBody(fn)
                    ? Type.Primitive.VOID
                    : TypeRef.unresolved("Any");
            // Expression-body functions without explicit type: Any; block without type: VOID.
            if (childNamed(fn, "function_body") != null && !hasBlockBody(fn)) {
                returnType = TypeRef.unresolved("Any");
            } else if (childNamed(fn, "function_body") != null && hasBlockBody(fn) && typeChild(fn) == null) {
                returnType = Type.Primitive.VOID;
            } else if (childNamed(fn, "function_body") == null && typeChild(fn) == null) {
                returnType = Type.Primitive.VOID;
            }
        }

        List<ParameterEntry> params = new ArrayList<>();
        boolean varargs = false;
        TsNode valueParams = childNamed(fn, "function_value_parameters");
        if (valueParams != null) {
            for (TsNode p : namedChildren(valueParams)) {
                if (!"parameter".equals(p.type())) continue;
                // vararg is on parameter_modifiers sibling / parent — check preceding modifiers on parent seq
                if (hasVarargNearby(valueParams, p)) varargs = true;
                Type pt = toTypeNode(typeChild(p), methodTypeParams, ownerJvm);
                if (pt == null) pt = TypeRef.unresolved("Any");
                params.add(new ParameterEntry(text(childNamed(p, "simple_identifier")),
                        0, pt, EmptyArrays.ANNOTATION_REF));
            }
        }

        return new MethodEntry(
                flags,
                name,
                returnType,
                EmptyArrays.toArray(params, EmptyArrays.PARAMETER),
                EmptyArrays.TYPE,
                EmptyArrays.toArray(typeParamRefs, EmptyArrays.TYPE_PARAM),
                varargs,
                childNamed(fn, "function_body") != null,
                null,
                EmptyArrays.ANNOTATION_REF);
    }

    private static boolean hasVarargNearby(TsNode valueParams, TsNode parameter) {
        // parameter_modifiers may be a sibling before parameter in grammar as _function_value_parameter
        // In CST, parameter_modifiers may wrap or sit as sibling; scan valueParams children textually.
        List<TsNode> kids = namedChildren(valueParams);
        for (int i = 0; i < kids.size(); i++) {
            if (kids.get(i) == parameter || kids.get(i).equals(parameter)) {
                // look at previous named sibling
                if (i > 0 && "parameter_modifiers".equals(kids.get(i - 1).type())) {
                    return text(kids.get(i - 1)).contains("vararg");
                }
            }
        }
        TsNode mods = childNamed(parameter, "parameter_modifiers");
        return mods != null && text(mods).contains("vararg");
    }

    private static MethodEntry toConstructorEntry(TsNode ctor, Set<String> typeParams, String ownerJvm) {
        int flags = visibilityFlags(ctor);
        List<ParameterEntry> params = new ArrayList<>();
        boolean varargs = false;
        if ("primary_constructor".equals(ctor.type())) {
            for (TsNode param : namedChildren(ctor)) {
                if (!"class_parameter".equals(param.type())) continue;
                Type pt = toTypeNode(typeChild(param), typeParams, ownerJvm);
                if (pt == null) pt = TypeRef.unresolved("Any");
                params.add(new ParameterEntry(text(childNamed(param, "simple_identifier")),
                        0, pt, EmptyArrays.ANNOTATION_REF));
            }
        } else {
            TsNode valueParams = childNamed(ctor, "function_value_parameters");
            if (valueParams != null) {
                for (TsNode p : namedChildren(valueParams)) {
                    if (!"parameter".equals(p.type())) continue;
                    if (hasVarargNearby(valueParams, p)) varargs = true;
                    Type pt = toTypeNode(typeChild(p), typeParams, ownerJvm);
                    if (pt == null) pt = TypeRef.unresolved("Any");
                    params.add(new ParameterEntry(text(childNamed(p, "simple_identifier")),
                            0, pt, EmptyArrays.ANNOTATION_REF));
                }
            }
        }
        return new MethodEntry(
                flags,
                "<init>",
                Type.Primitive.VOID,
                EmptyArrays.toArray(params, EmptyArrays.PARAMETER),
                EmptyArrays.TYPE,
                EmptyArrays.TYPE_PARAM,
                varargs,
                true,
                null,
                EmptyArrays.ANNOTATION_REF);
    }

    private static TypeParamRef toTypeParamRef(TsNode tp, Set<String> typeParams, String ownerJvm) {
        String name = text(childNamed(tp, "type_identifier"));
        if (name == null) name = "?";
        List<Type> bounds = new ArrayList<>();
        Type bound = toTypeNode(typeChild(tp), typeParams, ownerJvm);
        if (bound != null) bounds.add(bound);
        return new TypeParamRef(name, EmptyArrays.toArray(bounds, EmptyArrays.TYPE));
    }

    private static Type delegationType(TsNode del, Set<String> typeParams, String ownerJvm) {
        TsNode user = childNamed(del, "user_type");
        if (user != null) return toUserType(user, typeParams, ownerJvm);
        TsNode ctorInv = childNamed(del, "constructor_invocation");
        if (ctorInv != null) {
            TsNode ut = childNamed(ctorInv, "user_type");
            if (ut != null) return toUserType(ut, typeParams, ownerJvm);
        }
        TsNode explicit = childNamed(del, "explicit_delegation");
        if (explicit != null) {
            TsNode ut = childNamed(explicit, "user_type");
            if (ut != null) return toUserType(ut, typeParams, ownerJvm);
        }
        return TypeRef.unresolved("Any");
    }

    private static Type toTypeNode(TsNode typeNode, Set<String> typeParams, String ownerJvm) {
        if (typeNode == null) return null;
        return switch (typeNode.type()) {
            case "nullable_type" -> {
                Type inner = toTypeNode(firstNamedOfTypes(typeNode, "user_type", "parenthesized_type"),
                        typeParams, ownerJvm);
                yield inner != null ? inner : TypeRef.unresolved("Any");
            }
            case "user_type" -> toUserType(typeNode, typeParams, ownerJvm);
            case "parenthesized_type" -> toTypeNode(typeChild(typeNode), typeParams, ownerJvm);
            case "not_nullable_type" -> toTypeNode(typeChild(typeNode), typeParams, ownerJvm);
            case "function_type" -> TypeRef.unresolved("Function");
            default -> TypeRef.unresolved("Any");
        };
    }

    private static Type toUserType(TsNode user, Set<String> typeParams, String ownerJvm) {
        List<String> parts = new ArrayList<>();
        for (TsNode child : namedChildren(user)) {
            if ("type_identifier".equals(child.type())) {
                parts.add(text(child));
            }
        }
        if (parts.isEmpty()) return TypeRef.unresolved("Any");
        String referenced = parts.get(parts.size() - 1);
        Type primitive = primitiveOrUnit(referenced);
        if (primitive != null && parts.size() == 1) {
            return primitive;
        }
        if (typeParams.contains(referenced) && parts.size() == 1) {
            return Type.typeVariable(referenced);
        }

        TypeRef base;
        if (parts.size() == 1) {
            base = TypeRef.unresolved(parts.get(0));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(parts.get(i));
            }
            base = TypeRef.resolved(sb.toString());
        }

        TsNode argList = childNamed(user, "type_arguments");
        if (argList == null) {
            return base;
        }
        List<Type> args = new ArrayList<>();
        for (TsNode proj : namedChildren(argList)) {
            if (!"type_projection".equals(proj.type()) && typeChild(proj) == null
                    && !"user_type".equals(proj.type())
                    && !"nullable_type".equals(proj.type())) {
                continue;
            }
            Type arg = toTypeNode(typeChild(proj) != null ? typeChild(proj) : proj, typeParams, ownerJvm);
            if (arg == null && "user_type".equals(proj.type())) {
                arg = toUserType(proj, typeParams, ownerJvm);
            }
            if (arg == null) arg = TypeRef.unresolved("Any");
            // Skip punctuation-only children
            if ("*".equals(text(proj))) continue;
            args.add(arg);
        }
        if (args.isEmpty()) return base;
        return Type.parameterized(base, EmptyArrays.toArray(args, EmptyArrays.TYPE));
    }

    private static Type primitiveOrUnit(String name) {
        return switch (name) {
            case "Byte", "byte" -> Type.Primitive.BYTE;
            case "Short", "short" -> Type.Primitive.SHORT;
            case "Int", "int" -> Type.Primitive.INT;
            case "Long", "long" -> Type.Primitive.LONG;
            case "Float", "float" -> Type.Primitive.FLOAT;
            case "Double", "double" -> Type.Primitive.DOUBLE;
            case "Boolean", "boolean" -> Type.Primitive.BOOLEAN;
            case "Char", "char" -> Type.Primitive.CHAR;
            case "Unit", "void" -> Type.Primitive.VOID;
            default -> null;
        };
    }

    private static String parseFileJvmName(TsNode fileAnnotation) {
        // @file:JvmName("X") — look for user_type JvmName and a string literal child somewhere.
        String text = text(fileAnnotation);
        if (text == null || !text.contains("JvmName")) return null;
        int q1 = text.indexOf('"');
        if (q1 < 0) return null;
        int q2 = text.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        String value = text.substring(q1 + 1, q2);
        return value.isEmpty() ? null : value;
    }

    private static String defaultFileFacadeName(String fileName) {
        String base = fileName;
        if (base.toLowerCase().endsWith(".kt")) {
            base = base.substring(0, base.length() - 3);
        }
        if (base.isEmpty()) base = "File";
        char first = base.charAt(0);
        if (Character.isLowerCase(first)) {
            base = Character.toUpperCase(first) + base.substring(1);
        }
        return base + "Kt";
    }

    private static String typeSimpleName(TsNode typeNode) {
        return text(childNamed(typeNode, "type_identifier"));
    }

    private static String propertyName(TsNode prop) {
        TsNode varDecl = childNamed(prop, "variable_declaration");
        if (varDecl != null) {
            return text(childNamed(varDecl, "simple_identifier"));
        }
        TsNode multi = childNamed(prop, "multi_variable_declaration");
        if (multi != null) {
            TsNode first = childNamed(multi, "variable_declaration");
            if (first != null) return text(childNamed(first, "simple_identifier"));
        }
        return null;
    }

    private static Type propertyType(TsNode prop, Set<String> typeParams, String ownerJvm) {
        TsNode varDecl = childNamed(prop, "variable_declaration");
        if (varDecl != null) {
            return toTypeNode(typeChild(varDecl), typeParams, ownerJvm);
        }
        return null;
    }

    private static boolean isVarProperty(TsNode prop) {
        TsNode binding = childNamed(prop, "binding_pattern_kind");
        return binding != null && "var".equals(text(binding));
    }

    private static boolean hasBlockBody(TsNode fn) {
        TsNode body = childNamed(fn, "function_body");
        if (body == null) return false;
        return childNamed(body, "statements") != null;
    }

    private static TsNode typeChild(TsNode parent) {
        if (parent == null) return null;
        return firstNamedOfTypes(parent,
                "user_type", "nullable_type", "parenthesized_type",
                "not_nullable_type", "function_type");
    }

    private static String identifierToJvm(TsNode identifier) {
        if (identifier == null) return "";
        List<String> parts = new ArrayList<>();
        for (TsNode child : namedChildren(identifier)) {
            if ("simple_identifier".equals(child.type())) {
                parts.add(text(child));
            }
        }
        if (parts.isEmpty()) {
            String t = text(identifier);
            if (t == null || t.isEmpty()) return "";
            return t.replace('.', '/');
        }
        return String.join("/", parts);
    }

    private static List<TsNode> namedChildren(TsNode node) {
        if (node == null) return List.of();
        return node.namedChildren();
    }

    private static TsNode childNamed(TsNode parent, String type) {
        if (parent == null) return null;
        for (TsNode child : namedChildren(parent)) {
            if (type.equals(child.type())) return child;
        }
        return null;
    }

    private static TsNode firstNamedOfTypes(TsNode parent, String... types) {
        if (parent == null) return null;
        Set<String> wanted = Set.of(types);
        for (TsNode child : namedChildren(parent)) {
            if (wanted.contains(child.type())) return child;
        }
        return null;
    }

    /** True if {@code keyword} appears as a token in the declaration header (before the name). */
    private static boolean headerContainsKeyword(TsNode typeNode, String keyword) {
        if (typeNode == null || keyword == null || keyword.isEmpty()) return false;
        TsNode name = childNamed(typeNode, "type_identifier");
        int end = name != null ? name.startByte() : typeNode.endByte();
        String header = typeNode.textSlice(typeNode.startByte(), end);
        return containsKeywordToken(header, keyword);
    }

    private static boolean containsKeywordToken(String text, String keyword) {
        if (text == null || text.isEmpty()) return false;
        int from = 0;
        while (from <= text.length() - keyword.length()) {
            int idx = text.indexOf(keyword, from);
            if (idx < 0) return false;
            boolean startOk = idx == 0 || !isIdentChar(text.charAt(idx - 1));
            int after = idx + keyword.length();
            boolean endOk = after >= text.length() || !isIdentChar(text.charAt(after));
            if (startOk && endOk) return true;
            from = idx + 1;
        }
        return false;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String text(TsNode node) {
        if (node == null) return null;
        String t = node.text();
        return t == null || t.isEmpty() ? null : t;
    }

    private static String simpleName(String path) {
        if (path == null) return "Source.kt";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
