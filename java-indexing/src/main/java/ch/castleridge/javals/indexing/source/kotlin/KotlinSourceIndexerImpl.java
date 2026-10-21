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
import java.util.Set;

import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassBody;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtConstructor;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtEnumEntry;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtModifierListOwner;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtNullableType;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtPrimaryConstructor;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtSecondaryConstructor;
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry;
import org.jetbrains.kotlin.psi.KtTypeAlias;
import org.jetbrains.kotlin.psi.KtTypeArgumentList;
import org.jetbrains.kotlin.psi.KtTypeElement;
import org.jetbrains.kotlin.psi.KtTypeParameter;
import org.jetbrains.kotlin.psi.KtTypeProjection;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.KtUserType;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.indexing.index.Index;
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
 * Walks a parse-only Kotlin PSI tree and emits {@link SourceTypeEntry}
 * declarations plus an identifier bloom filter.
 */
final class KotlinSourceIndexerImpl {

    private KotlinSourceIndexerImpl() {}

    static void index(String resourcePath, String sourceUri, CharSequence content, Index into) {
        if (resourcePath == null || sourceUri == null || content == null || into == null) {
            return;
        }
        String fileName = simpleName(resourcePath);
        try {
            KotlinPsiEnvironment.withParsed(fileName, content, ktFile -> {
                indexFile(resourcePath, sourceUri, ktFile, into);
                try {
                    into.registerBloom(sourceUri, resourcePath,
                            KotlinIdentifierCollector.collectAndBuild(ktFile));
                } catch (RuntimeException | StackOverflowError e) {
                    System.err.println("Failed to build identifier bloom filter for " + resourcePath
                            + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            });
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("Failed to index Kotlin source " + resourcePath
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void indexFile(String resourcePath, String sourceUri, KtFile file, Index into) {
        FqName pkgFq = file.getPackageFqName();
        String packageJvm = pkgFq == null || pkgFq.isRoot() ? "" : pkgFq.asString().replace('.', '/');

        Map<String, String> singleTypeImports = new HashMap<>();
        List<String> onDemandImports = new ArrayList<>();
        file.getImportDirectives().forEach(imp -> {
            if (imp.isAllUnder()) {
                FqName imported = imp.getImportedFqName();
                if (imported != null) {
                    onDemandImports.add(imported.asString().replace('.', '/'));
                }
                return;
            }
            FqName imported = imp.getImportedFqName();
            if (imported == null) return;
            Name shortName = imported.shortNameOrSpecial();
            if (shortName.isSpecial()) return;
            String alias = imp.getAliasName();
            String simple = alias != null ? alias : shortName.asString();
            singleTypeImports.put(simple, imported.asString().replace('.', '/'));
        });

        Set<String> siblings = new LinkedHashSet<>();
        for (KtDeclaration decl : file.getDeclarations()) {
            if (decl instanceof KtClassOrObject co) {
                String name = co.getName();
                if (name != null && !name.isEmpty()) siblings.add(name);
            }
        }
        String facadeSimple = fileFacadeSimpleName(file, fileName(resourcePath));
        boolean hasTopLevelMembers = hasTopLevelCallable(file);
        if (hasTopLevelMembers) {
            siblings.add(facadeSimple);
        }

        SourceResolutionHints hints = new SourceResolutionHints(
                packageJvm,
                singleTypeImports,
                EmptyArrays.toArray(onDemandImports, EmptyArrays.STRING),
                siblings);

        Deque<String> enclosing = new ArrayDeque<>();
        for (KtDeclaration decl : file.getDeclarations()) {
            if (decl instanceof KtClassOrObject co) {
                indexClassOrObject(resourcePath, sourceUri, co, packageJvm, enclosing,
                        new HashSet<>(), hints, into);
            }
        }

        if (hasTopLevelMembers) {
            indexFileFacade(resourcePath, sourceUri, file, packageJvm, facadeSimple, hints, into);
        }
    }

    private static boolean hasTopLevelCallable(KtFile file) {
        for (KtDeclaration decl : file.getDeclarations()) {
            if (decl instanceof KtNamedFunction || decl instanceof KtProperty) {
                return true;
            }
        }
        return false;
    }

    private static void indexFileFacade(String resourcePath, String sourceUri, KtFile file,
                                        String packageJvm, String facadeSimple,
                                        SourceResolutionHints hints, Index into) {
        String jvmOwner = packageJvm.isEmpty() ? facadeSimple : packageJvm + "/" + facadeSimple;
        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();

        for (KtDeclaration decl : file.getDeclarations()) {
            if (decl instanceof KtProperty prop) {
                FieldEntry field = toFieldEntry(prop, Set.of(), jvmOwner, true);
                if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                    fields.add(field);
                }
                MethodEntry getter = syntheticPropertyAccessor(prop, true, true, Set.of(), jvmOwner);
                if (getter != null) methods.add(getter);
                if (prop.isVar()) {
                    MethodEntry setter = syntheticPropertyAccessor(prop, false, true, Set.of(), jvmOwner);
                    if (setter != null) methods.add(setter);
                }
            } else if (decl instanceof KtNamedFunction fn) {
                MethodEntry method = toMethodEntry(fn, Set.of(), jvmOwner, true);
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

    private static void indexClassOrObject(String resourcePath, String sourceUri,
                                           KtClassOrObject co, String packageJvm,
                                           Deque<String> enclosing, Set<String> outerTypeParams,
                                           SourceResolutionHints hints, Index into) {
        String simple = co.getName();
        if ((simple == null || simple.isEmpty())
                && co instanceof KtObjectDeclaration obj && obj.isCompanion()) {
            simple = "Companion";
        }
        if (simple == null || simple.isEmpty()) {
            // Anonymous object — skip as a named index entry.
            return;
        }

        String localName;
        if (enclosing.isEmpty()) {
            localName = packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
        } else {
            localName = enclosing.peekLast() + "$" + simple;
        }

        if (!AccessVisibility.shouldIndexSourceType(visibilityFlags(co))) {
            return;
        }

        Set<String> typeParams = new HashSet<>(outerTypeParams);
        List<TypeParamRef> declaredTypeParams = new ArrayList<>();
        if (co instanceof KtClass klass) {
            for (KtTypeParameter tp : klass.getTypeParameters()) {
                String tpName = tp.getName();
                if (tpName != null) typeParams.add(tpName);
            }
            for (KtTypeParameter tp : klass.getTypeParameters()) {
                declaredTypeParams.add(toTypeParamRef(tp, typeParams, localName));
            }
        }

        TypeDeclKind declKind = declKind(co);
        int modifiers = typeModifiers(co, declKind);

        Type superRef = null;
        List<Type> interfaceRefs = new ArrayList<>();
        for (KtSuperTypeListEntry entry : co.getSuperTypeListEntries()) {
            Type t = toType(entry.getTypeReference(), typeParams, localName);
            if (t == null) continue;
            // Without resolve we cannot tell class vs interface; treat the first
            // as superclass for classes/objects and all as interfaces for interfaces.
            if (declKind == TypeDeclKind.INTERFACE || declKind == TypeDeclKind.ANNOTATION) {
                interfaceRefs.add(t);
            } else if (superRef == null && !(co instanceof KtObjectDeclaration)) {
                superRef = t;
            } else {
                interfaceRefs.add(t);
            }
        }

        List<FieldEntry> fields = new ArrayList<>();
        List<MethodEntry> methods = new ArrayList<>();
        List<String> innerTypes = new ArrayList<>();
        List<KtClassOrObject> nested = new ArrayList<>();

        // Primary constructor parameters that are also properties.
        KtPrimaryConstructor primary = co.getPrimaryConstructor();
        if (primary != null) {
            MethodEntry ctor = toConstructorEntry(primary, typeParams, localName, declKind);
            if (ctor != null && AccessVisibility.shouldIndexMember(ctor.modifiers(), ctor.name())) {
                methods.add(ctor);
            }
            for (KtParameter param : primary.getValueParameters()) {
                if (param.hasValOrVar()) {
                    FieldEntry field = constructorPropertyField(param, typeParams, localName);
                    if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                        fields.add(field);
                    }
                }
            }
        } else if (declKind != TypeDeclKind.INTERFACE && declKind != TypeDeclKind.ANNOTATION
                && !(co instanceof KtObjectDeclaration)) {
            // Implicit default constructor.
            methods.add(new MethodEntry(
                    Opcodes.ACC_PUBLIC, "<init>", Type.Primitive.VOID,
                    EmptyArrays.TYPE, EmptyArrays.TYPE, EmptyArrays.ANNOTATION_REF));
        }

        if (co instanceof KtObjectDeclaration) {
            // Kotlin objects expose a static INSTANCE field to Java.
            fields.add(new FieldEntry(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "INSTANCE",
                    TypeRef.resolved(localName),
                    EmptyArrays.ANNOTATION_REF));
        }

        KtClassBody body = co.getBody();
        if (body != null) {
            for (KtDeclaration member : body.getDeclarations()) {
                if (member instanceof KtEnumEntry enumEntry) {
                    FieldEntry field = enumConstantField(enumEntry, localName);
                    if (field != null) fields.add(field);
                    continue;
                }
                if (member instanceof KtProperty prop) {
                    FieldEntry field = toFieldEntry(prop, typeParams, localName, false);
                    if (field != null && AccessVisibility.shouldIndexMember(field.modifiers(), field.name())) {
                        fields.add(field);
                    }
                    MethodEntry getter = syntheticPropertyAccessor(prop, true, false, typeParams, localName);
                    if (getter != null) methods.add(getter);
                    if (prop.isVar()) {
                        MethodEntry setter = syntheticPropertyAccessor(prop, false, false, typeParams, localName);
                        if (setter != null) methods.add(setter);
                    }
                } else if (member instanceof KtNamedFunction fn) {
                    MethodEntry method = toMethodEntry(fn, typeParams, localName, false);
                    if (method != null && AccessVisibility.shouldIndexMember(method.modifiers(), method.name())) {
                        methods.add(method);
                    }
                } else if (member instanceof KtSecondaryConstructor secondary) {
                    MethodEntry ctor = toConstructorEntry(secondary, typeParams, localName, declKind);
                    if (ctor != null && AccessVisibility.shouldIndexMember(ctor.modifiers(), ctor.name())) {
                        methods.add(ctor);
                    }
                } else if (member instanceof KtClassOrObject nestedCo) {
                    if (!AccessVisibility.shouldIndexSourceType(visibilityFlags(nestedCo))) {
                        continue;
                    }
                    String nestedName = nestedCo.getName();
                    if (nestedCo instanceof KtObjectDeclaration obj && obj.isCompanion()
                            && (nestedName == null || nestedName.isEmpty())) {
                        nestedName = "Companion";
                    }
                    if (nestedName == null || nestedName.isEmpty()) continue;
                    nested.add(nestedCo);
                    innerTypes.add(localName + "$" + nestedName);
                } else if (member instanceof KtTypeAlias) {
                    // Type aliases are compile-time only; skip JVM entries.
                }
            }
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
            for (KtClassOrObject child : nested) {
                indexClassOrObject(resourcePath, sourceUri, child, packageJvm, enclosing,
                        typeParams, hints, into);
            }
        } finally {
            enclosing.removeLast();
        }
    }

    private static TypeDeclKind declKind(KtClassOrObject co) {
        if (co instanceof KtClass klass) {
            if (klass.isInterface()) return TypeDeclKind.INTERFACE;
            if (klass.isEnum()) return TypeDeclKind.ENUM;
            if (klass.isAnnotation()) return TypeDeclKind.ANNOTATION;
            if (klass.isData()) return TypeDeclKind.CLASS;
        }
        return TypeDeclKind.CLASS;
    }

    private static int typeModifiers(KtClassOrObject co, TypeDeclKind kind) {
        int flags = visibilityFlags(co);
        if (kind == TypeDeclKind.INTERFACE || kind == TypeDeclKind.ANNOTATION) {
            flags |= Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;
            if (kind == TypeDeclKind.ANNOTATION) {
                flags |= Opcodes.ACC_ANNOTATION;
            }
        } else if (kind == TypeDeclKind.ENUM) {
            flags |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        } else {
            flags |= Opcodes.ACC_SUPER;
            if (co.hasModifier(KtTokens.ABSTRACT_KEYWORD) || co.hasModifier(KtTokens.SEALED_KEYWORD)) {
                flags |= Opcodes.ACC_ABSTRACT;
            } else if (!co.hasModifier(KtTokens.OPEN_KEYWORD)) {
                flags |= Opcodes.ACC_FINAL;
            }
        }
        if (co instanceof KtObjectDeclaration) {
            flags |= Opcodes.ACC_FINAL;
        }
        if (co.hasModifier(KtTokens.INNER_KEYWORD)) {
            // Non-static nested; leave ACC_STATIC clear.
        } else if (!enclosingWouldBeTopLevel(co) && !(co instanceof KtEnumEntry)) {
            // Nested class/object/companion defaults to static nested from Java's view
            // except for `inner` classes.
            if (co.getParent() instanceof KtClassBody) {
                flags |= Opcodes.ACC_STATIC;
            }
        }
        return flags;
    }

    private static boolean enclosingWouldBeTopLevel(KtClassOrObject co) {
        org.jetbrains.kotlin.com.intellij.psi.PsiElement parent = co.getParent();
        while (parent != null) {
            if (parent instanceof KtClassOrObject) return false;
            if (parent instanceof KtFile) return true;
            parent = parent.getParent();
        }
        return true;
    }

    private static int visibilityFlags(KtModifierListOwner owner) {
        if (owner.hasModifier(KtTokens.PRIVATE_KEYWORD)) return Opcodes.ACC_PRIVATE;
        if (owner.hasModifier(KtTokens.PROTECTED_KEYWORD)) return Opcodes.ACC_PROTECTED;
        if (owner.hasModifier(KtTokens.INTERNAL_KEYWORD)) return 0; // package-private to Java
        return Opcodes.ACC_PUBLIC;
    }

    private static FieldEntry toFieldEntry(KtProperty prop, Set<String> typeParams,
                                           String ownerJvm, boolean topLevel) {
        String name = prop.getName();
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(prop);
        if (topLevel || prop.hasModifier(KtTokens.CONST_KEYWORD) || !prop.isVar()) {
            // Top-level and val properties compile to static fields (with accessors);
            // const val is always static final.
        }
        if (topLevel) {
            flags |= Opcodes.ACC_STATIC;
        }
        if (!prop.isVar() || prop.hasModifier(KtTokens.CONST_KEYWORD)) {
            flags |= Opcodes.ACC_FINAL;
        }
        if (prop.hasModifier(KtTokens.CONST_KEYWORD)) {
            flags |= Opcodes.ACC_STATIC;
        }
        Type type = toType(prop.getTypeReference(), typeParams, ownerJvm);
        if (type == null) type = TypeRef.unresolved("Any");
        return new FieldEntry(flags, name, type, EmptyArrays.ANNOTATION_REF);
    }

    private static FieldEntry constructorPropertyField(KtParameter param, Set<String> typeParams,
                                                       String ownerJvm) {
        String name = param.getName();
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(param);
        if (!param.isMutable()) flags |= Opcodes.ACC_FINAL;
        Type type = toType(param.getTypeReference(), typeParams, ownerJvm);
        if (type == null) type = TypeRef.unresolved("Any");
        return new FieldEntry(flags, name, type, EmptyArrays.ANNOTATION_REF);
    }

    private static FieldEntry enumConstantField(KtEnumEntry entry, String ownerJvm) {
        String name = entry.getName();
        if (name == null || name.isEmpty()) return null;
        return new FieldEntry(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                name,
                TypeRef.resolved(ownerJvm),
                EmptyArrays.ANNOTATION_REF);
    }

    private static MethodEntry syntheticPropertyAccessor(KtProperty prop, boolean getter,
                                                         boolean topLevel,
                                                         Set<String> typeParams, String ownerJvm) {
        String name = prop.getName();
        if (name == null || name.isEmpty()) return null;
        // Private properties without annotated JVM field still get accessors when
        // visible; skip private to match AccessVisibility on members.
        int flags = visibilityFlags(prop);
        if (AccessVisibility.isPrivate(flags)) return null;
        if (topLevel) flags |= Opcodes.ACC_STATIC;
        Type propType = toType(prop.getTypeReference(), typeParams, ownerJvm);
        if (propType == null) propType = TypeRef.unresolved("Any");
        if (getter) {
            String methodName = "get" + capitalize(name);
            if (propType == Type.Primitive.BOOLEAN || isBooleanName(prop.getTypeReference())) {
                methodName = "is" + capitalize(name);
                // Prefer isX when property already starts with "is".
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

    private static boolean isBooleanName(KtTypeReference ref) {
        if (ref == null) return false;
        KtTypeElement el = ref.getTypeElement();
        if (el instanceof KtUserType user) {
            String n = user.getReferencedName();
            return "Boolean".equals(n) || "boolean".equals(n);
        }
        return false;
    }

    private static MethodEntry toMethodEntry(KtNamedFunction fn, Set<String> typeParams,
                                             String ownerJvm, boolean topLevel) {
        String name = fn.getName();
        if (name == null || name.isEmpty()) return null;
        int flags = visibilityFlags(fn);
        if (topLevel) flags |= Opcodes.ACC_STATIC;
        if (fn.hasModifier(KtTokens.ABSTRACT_KEYWORD)) flags |= Opcodes.ACC_ABSTRACT;
        if (fn.hasModifier(KtTokens.FINAL_KEYWORD)) flags |= Opcodes.ACC_FINAL;
        if (fn.hasModifier(KtTokens.OPEN_KEYWORD)) {
            flags &= ~Opcodes.ACC_FINAL;
        }

        Set<String> methodTypeParams = new HashSet<>(typeParams);
        List<TypeParamRef> typeParamRefs = new ArrayList<>();
        for (KtTypeParameter tp : fn.getTypeParameters()) {
            String tpName = tp.getName();
            if (tpName != null) methodTypeParams.add(tpName);
        }
        for (KtTypeParameter tp : fn.getTypeParameters()) {
            typeParamRefs.add(toTypeParamRef(tp, methodTypeParams, ownerJvm));
        }

        Type returnType = toType(fn.getTypeReference(), methodTypeParams, ownerJvm);
        if (returnType == null) {
            returnType = fn.hasBlockBody() ? Type.Primitive.VOID : TypeRef.unresolved("Any");
        }

        List<ParameterEntry> params = new ArrayList<>();
        boolean varargs = false;
        for (KtParameter param : fn.getValueParameters()) {
            if (param.isVarArg()) varargs = true;
            Type pt = toType(param.getTypeReference(), methodTypeParams, ownerJvm);
            if (pt == null) pt = TypeRef.unresolved("Any");
            params.add(new ParameterEntry(param.getName(), 0, pt, EmptyArrays.ANNOTATION_REF));
        }

        return new MethodEntry(
                flags,
                name,
                returnType,
                EmptyArrays.toArray(params, EmptyArrays.PARAMETER),
                EmptyArrays.TYPE,
                EmptyArrays.toArray(typeParamRefs, EmptyArrays.TYPE_PARAM),
                varargs,
                fn.hasBody(),
                null,
                EmptyArrays.ANNOTATION_REF);
    }

    private static MethodEntry toConstructorEntry(KtConstructor<?> ctor, Set<String> typeParams,
                                                  String ownerJvm, TypeDeclKind declKind) {
        int flags = visibilityFlags(ctor);
        List<ParameterEntry> params = new ArrayList<>();
        boolean varargs = false;
        for (KtParameter param : ctor.getValueParameters()) {
            if (param.isVarArg()) varargs = true;
            Type pt = toType(param.getTypeReference(), typeParams, ownerJvm);
            if (pt == null) pt = TypeRef.unresolved("Any");
            params.add(new ParameterEntry(param.getName(), 0, pt, EmptyArrays.ANNOTATION_REF));
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

    private static TypeParamRef toTypeParamRef(KtTypeParameter tp, Set<String> typeParams,
                                               String ownerJvm) {
        String name = tp.getName() == null ? "?" : tp.getName();
        List<Type> bounds = new ArrayList<>();
        KtTypeReference extendsBound = tp.getExtendsBound();
        if (extendsBound != null) {
            Type b = toType(extendsBound, typeParams, ownerJvm);
            if (b != null) bounds.add(b);
        }
        return new TypeParamRef(name, EmptyArrays.toArray(bounds, EmptyArrays.TYPE));
    }

    private static Type toType(KtTypeReference ref, Set<String> typeParams, String ownerJvm) {
        if (ref == null) return null;
        return toTypeElement(ref.getTypeElement(), typeParams, ownerJvm);
    }

    private static Type toTypeElement(KtTypeElement element, Set<String> typeParams, String ownerJvm) {
        if (element == null) return null;
        if (element instanceof KtNullableType nullable) {
            Type inner = toTypeElement(nullable.getInnerType(), typeParams, ownerJvm);
            return inner != null ? inner : TypeRef.unresolved("Any");
        }
        if (element instanceof KtUserType user) {
            return toUserType(user, typeParams, ownerJvm);
        }
        return TypeRef.unresolved("Any");
    }

    private static Type toUserType(KtUserType user, Set<String> typeParams, String ownerJvm) {
        String referenced = user.getReferencedName();
        if (referenced == null || referenced.isEmpty()) {
            return TypeRef.unresolved("Any");
        }
        Type primitive = primitiveOrUnit(referenced);
        if (primitive != null && user.getQualifier() == null) {
            return primitive;
        }
        if (typeParams.contains(referenced) && user.getQualifier() == null) {
            return Type.typeVariable(referenced);
        }

        List<String> parts = new ArrayList<>();
        KtUserType cur = user;
        while (cur != null) {
            String part = cur.getReferencedName();
            if (part != null) parts.add(0, part);
            cur = cur.getQualifier();
        }

        TypeRef base;
        if (parts.size() == 1) {
            base = TypeRef.unresolved(parts.get(0));
        } else {
            // Without resolve, treat a qualified name as a package/type JVM path.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(parts.get(i));
            }
            base = TypeRef.resolved(sb.toString());
        }

        KtTypeArgumentList argList = user.getTypeArgumentList();
        if (argList == null || argList.getArguments().isEmpty()) {
            return base;
        }
        List<Type> args = new ArrayList<>();
        for (KtTypeProjection proj : argList.getArguments()) {
            Type arg = toType(proj.getTypeReference(), typeParams, ownerJvm);
            args.add(arg == null ? TypeRef.unresolved("Any") : arg);
        }
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

    private static String fileFacadeSimpleName(KtFile file, String fileName) {
        for (KtAnnotationEntry entry : file.getAnnotationEntries()) {
            // @file:JvmName("X") lives on file annotation list; short name JvmName.
            String shortName = entry.getShortName() == null ? null : entry.getShortName().asString();
            if ("JvmName".equals(shortName)) {
                String value = firstAnnotationStringArg(entry);
                if (value != null && !value.isEmpty()) return value;
            }
        }
        return defaultFileFacadeName(fileName);
    }

    private static String firstAnnotationStringArg(KtAnnotationEntry entry) {
        var args = entry.getValueArguments();
        if (args == null || args.isEmpty()) return null;
        var arg = args.get(0).getArgumentExpression();
        if (arg == null) return null;
        String text = arg.getText();
        if (text == null) return null;
        text = text.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return null;
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

    private static String fileName(String resourcePath) {
        return simpleName(resourcePath);
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
