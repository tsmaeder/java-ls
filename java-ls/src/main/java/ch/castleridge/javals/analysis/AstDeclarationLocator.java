/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.lsp4j.Location;

import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.EnumConstantDecl;
import ch.castleridge.javals.ast.FieldDecl;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.MethodSymbol;
import ch.castleridge.javals.ast.ParamDecl;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.ast.TypeName;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.TypeNode;
import ch.castleridge.javals.ast.VarFragment;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Locates a declaration in attached source by diet-lowering the companion
 * {@code .java} and matching type / method / field names structurally.
 */
public final class AstDeclarationLocator {

    public interface DietLowerer {
        CompilationUnit lower(String uri);
    }

    private static final int DEFAULT_CAPACITY = 64;

    private final DietLowerer lowerer;
    private final Map<String, CompilationUnit> cache;

    public AstDeclarationLocator(DietLowerer lowerer) {
        this(lowerer, DEFAULT_CAPACITY);
    }

    public AstDeclarationLocator(DietLowerer lowerer, int capacity) {
        this.lowerer = lowerer;
        this.cache = Collections.synchronizedMap(new LruMap<>(capacity));
    }

    public void invalidate(String uri) {
        if (uri != null) cache.remove(uri);
    }

    public Optional<Location> locateType(TypeEntry entry, Map<String, String> sourceJarByBinaryJar) {
        if (entry == null) return Optional.empty();
        Optional<String> sourceUri = AttachedSource.javaUri(
                entry.resourceUri(), entry.sourceUri(), sourceJarByBinaryJar);
        if (sourceUri.isEmpty()) return Optional.empty();
        CompilationUnit cu = unit(sourceUri.get());
        if (cu == null) return Optional.empty();
        TypeDecl type = findType(cu, entry.jvmOwnerName());
        if (type == null || type.name() == null) return Optional.empty();
        return Optional.of(AstPositions.location(sourceUri.get(), cu.source(), type.name().range()));
    }

    public Optional<Location> locate(Symbol symbol, TypeEntry owner, Map<String, String> sourceJarByBinaryJar) {
        if (symbol == null || owner == null) return Optional.empty();
        Optional<String> sourceUri = AttachedSource.javaUri(
                owner.resourceUri(), owner.sourceUri(), sourceJarByBinaryJar);
        if (sourceUri.isEmpty()) return Optional.empty();
        CompilationUnit cu = unit(sourceUri.get());
        if (cu == null) return Optional.empty();
        TypeDecl type = findType(cu, owner.jvmOwnerName());
        if (type == null) return Optional.empty();
        Identifier name = declaredName(type, symbol);
        if (name == null || !name.range().isPresent()) {
            name = type.name();
        }
        if (name == null) return Optional.empty();
        return Optional.of(AstPositions.location(sourceUri.get(), cu.source(), name.range()));
    }

    private CompilationUnit unit(String uri) {
        CompilationUnit cached = cache.get(uri);
        if (cached != null) return cached;
        CompilationUnit fresh = lowerer.lower(uri);
        if (fresh == null) return null;
        cache.put(uri, fresh);
        return fresh;
    }

    static TypeDecl findType(CompilationUnit cu, String jvmOwnerName) {
        if (cu == null || jvmOwnerName == null || jvmOwnerName.isEmpty()) return null;
        String pkg = cu.packageDecl() == null ? "" : cu.packageDecl().qualifiedName().replace('.', '/');
        for (TypeDecl type : cu.types()) {
            String simple = type.name() == null ? "" : type.name().name();
            String jvm = pkg.isEmpty() ? simple : pkg + "/" + simple;
            TypeDecl found = findType(type, jvm, jvmOwnerName);
            if (found != null) return found;
        }
        return null;
    }

    private static TypeDecl findType(TypeDecl type, String jvmName, String wanted) {
        if (wanted.equals(jvmName)) return type;
        if (!wanted.startsWith(jvmName + "$")) return null;
        for (Declaration member : type.members()) {
            if (member instanceof TypeDecl nested) {
                String simple = nested.name() == null ? "" : nested.name().name();
                TypeDecl found = findType(nested, jvmName + "$" + simple, wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Identifier declaredName(TypeDecl type, Symbol symbol) {
        if (symbol instanceof TypeSymbol) return type.name();
        if (symbol instanceof MethodSymbol method) {
            Identifier found = findMethod(type, method);
            return found != null ? found : type.name();
        }
        Identifier field = findField(type, symbol.name());
        return field != null ? field : type.name();
    }

    private static Identifier findMethod(TypeDecl type, MethodSymbol wanted) {
        String wantName = wanted.constructor()
                ? (type.name() == null ? wanted.name() : type.name().name())
                : wanted.name();
        int arity = wanted.parameterTypes().size();
        List<String> wantParams = wanted.parameterTypes().stream().map(Object::toString).toList();
        Identifier byArity = null;
        for (Declaration member : type.members()) {
            Identifier name;
            List<ParamDecl> params;
            boolean constructor;
            if (member instanceof MethodDecl method) {
                name = method.name();
                params = method.parameters();
                constructor = false;
            } else if (member instanceof ConstructorDecl ctor) {
                name = ctor.name();
                params = ctor.parameters();
                constructor = true;
            } else {
                continue;
            }
            if (constructor != wanted.constructor()) continue;
            if (name == null || !wantName.equals(name.name())) continue;
            if (params.size() != arity) continue;
            if (byArity == null) byArity = name;
            if (sourceParamNames(params).equals(simpleNames(wantParams))) return name;
            if (equalsIgnoreCase(sourceParamNames(params), simpleNames(wantParams))) return name;
        }
        if (byArity != null) return byArity;
        return findField(type, wantName);
    }

    private static Identifier findField(TypeDecl type, String name) {
        for (Declaration member : type.members()) {
            if (member instanceof FieldDecl field) {
                for (VarFragment fragment : field.fragments()) {
                    if (fragment.name() != null && name.equals(fragment.name().name())) {
                        return fragment.name();
                    }
                }
            } else if (member instanceof EnumConstantDecl constant
                    && constant.name() != null && name.equals(constant.name().name())) {
                return constant.name();
            } else if (member instanceof RecordComponentDecl component
                    && component.name() != null && name.equals(component.name().name())) {
                return component.name();
            }
        }
        for (RecordComponentDecl component : type.recordComponents()) {
            if (component.name() != null && name.equals(component.name().name())) {
                return component.name();
            }
        }
        return null;
    }

    private static List<String> sourceParamNames(List<ParamDecl> params) {
        List<String> out = new ArrayList<>(params.size());
        for (ParamDecl param : params) out.add(simpleTypeName(param.type()));
        return out;
    }

    private static List<String> simpleNames(List<String> types) {
        List<String> out = new ArrayList<>(types.size());
        for (String type : types) {
            String name = type;
            int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf('/'));
            if (cut >= 0) name = name.substring(cut + 1);
            cut = name.lastIndexOf('$');
            if (cut >= 0) name = name.substring(cut + 1);
            out.add(name);
        }
        return out;
    }

    private static boolean equalsIgnoreCase(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equalsIgnoreCase(b.get(i))) return false;
        }
        return true;
    }

    private static String simpleTypeName(TypeNode tree) {
        if (tree == null) return "?";
        return switch (tree) {
            case TypeName name -> name.simpleName() == null ? "?" : name.simpleName().name();
            case ch.castleridge.javals.ast.ArrayTypeNode array -> simpleTypeName(array.element()) + "[]";
            case ch.castleridge.javals.ast.ParameterizedTypeNode param -> simpleTypeName(param.raw());
            case ch.castleridge.javals.ast.PrimitiveTypeNode primitive -> primitive.kind().name().toLowerCase();
            case ch.castleridge.javals.ast.VoidTypeNode ignored -> "void";
            default -> tree.toString();
        };
    }

    private static final class LruMap<K, V> extends LinkedHashMap<K, V> {
        private final int capacity;

        LruMap(int capacity) {
            super(capacity, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }
}
