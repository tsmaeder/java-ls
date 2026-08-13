/**
 * Copyright 2026 by Anysphere Inc.
 * 
 * Licensed under the MIT License.
 * 
 * SPDX-License-Identifier: MIT
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

import ch.castleridge.javals.analysis.AttachedSource;
import ch.castleridge.javals.analysis.SourceText;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Locates the declaration of a symbol that the analysed unit does not
 * declare itself: a type, method or field of another workspace file, of a
 * dependency jar, or of the JDK.
 *
 * <p>The index says which resource declares the symbol; {@link AttachedSource}
 * turns that into a {@code .java} URI (directly for source entries, via the
 * attached sources archive for class files). That file is diet-parsed with
 * ECJ - declarations and their positions, no method bodies - and the
 * declaration is matched structurally: types by JVM binary name, methods by
 * selector plus parameter type names, fields by name.
 *
 * <p>Members ECJ synthesises rather than reads from source (an enum's
 * {@code values()}, a record's accessors) have no declaration to match, and
 * resolve to their declaring type's name instead.
 *
 * <p>Parses are cached per source URI; the cache is bounded because a single
 * navigation session can otherwise pull in every source file of a
 * {@code src.zip}.
 */
public final class EcjDeclarationLocator {

    private static final int DEFAULT_CAPACITY = 64;

    private final Map<String, ParsedSource> cache;

    public EcjDeclarationLocator() {
        this(DEFAULT_CAPACITY);
    }

    public EcjDeclarationLocator(int capacity) {
        this.cache = Collections.synchronizedMap(new LruMap<>(capacity));
    }

    /**
     * Declaration of {@code binding} inside the source of {@code owner}, the
     * indexed type that declares it. Empty when the owner has no readable
     * source - notably a class file whose container has no attached sources
     * archive.
     */
    public Optional<Location> locate(TypeEntry owner,
                                     Binding binding,
                                     Map<String, String> sourceJarByBinaryJar) {
        if (owner == null || binding == null) return Optional.empty();
        Optional<String> sourceUri =
                AttachedSource.javaUri(owner.resourceUri(), owner.sourceUri(), sourceJarByBinaryJar);
        if (sourceUri.isEmpty()) return Optional.empty();

        ParsedSource parsed = parse(sourceUri.get());
        if (parsed == null) return Optional.empty();

        TypeDeclaration type = findType(parsed.unit(), owner.jvmOwnerName());
        if (type == null) return Optional.empty();

        return Optional.of(location(sourceUri.get(), parsed.text(), declaredName(type, binding)));
    }

    /**
     * Declaration of an indexed type in its attached source. Used by type
     * hierarchy when walking parents/children that are not the open buffer.
     */
    public Optional<Location> locateType(TypeEntry owner, Map<String, String> sourceJarByBinaryJar) {
        if (owner == null) return Optional.empty();
        Optional<String> sourceUri =
                AttachedSource.javaUri(owner.resourceUri(), owner.sourceUri(), sourceJarByBinaryJar);
        if (sourceUri.isEmpty()) return Optional.empty();

        ParsedSource parsed = parse(sourceUri.get());
        if (parsed == null) return Optional.empty();

        TypeDeclaration type = findType(parsed.unit(), owner.jvmOwnerName());
        if (type == null) return Optional.empty();

        return Optional.of(location(sourceUri.get(), parsed.text(),
                new DeclaredName(type.name, type.sourceStart, type.declarationSourceStart)));
    }

    /** Drop the cached parse of {@code uri}, whose content changed. */
    public void invalidate(String uri) {
        if (uri != null) cache.remove(uri);
    }

    private ParsedSource parse(String uri) {
        ParsedSource cached = cache.get(uri);
        if (cached != null) return cached;
        ParsedSource fresh = parseFresh(uri);
        if (fresh == null) return null;
        cache.put(uri, fresh);
        return fresh;
    }

    private static ParsedSource parseFresh(String uri) {
        String text = SourceText.read(uri);
        if (text == null) return null;

        CompilerOptions options = new CompilerOptions();
        options.sourceLevel = CompilerOptions.versionToJdkLevel(CompilerOptions.getLatestVersion());
        options.complianceLevel = options.sourceLevel;
        options.targetJDK = options.sourceLevel;
        ProblemReporter reporter = new ProblemReporter(
                DefaultErrorHandlingPolicies.proceedWithAllProblems(),
                options,
                new DefaultProblemFactory(Locale.ROOT));
        Parser parser = new Parser(reporter, false);
        ICompilationUnit input = new CompilationUnit(text.toCharArray(), uri, "UTF-8");
        CompilationResult result = new CompilationResult(input, 0, 1, options.maxProblemsPerUnit);
        try {
            CompilationUnitDeclaration unit = parser.dietParse(input, result);
            if (unit == null || unit.types == null) return null;
            return new ParsedSource(text, unit);
        } catch (RuntimeException | StackOverflowError failure) {
            return null;
        }
    }

    /** Type whose JVM binary name is {@code jvmOwnerName}, nested types included. */
    private static TypeDeclaration findType(CompilationUnitDeclaration unit, String jvmOwnerName) {
        if (unit.types == null || jvmOwnerName == null || jvmOwnerName.isEmpty()) return null;
        String packagePrefix = packagePrefix(unit);
        for (TypeDeclaration type : unit.types) {
            if (type == null || type.name == null) continue;
            TypeDeclaration found = findType(type, packagePrefix + new String(type.name), jvmOwnerName);
            if (found != null) return found;
        }
        return null;
    }

    private static TypeDeclaration findType(TypeDeclaration type, String jvmName, String wanted) {
        if (jvmName.equals(wanted)) return type;
        if (!wanted.startsWith(jvmName + "$") || type.memberTypes == null) return null;
        for (TypeDeclaration member : type.memberTypes) {
            if (member == null || member.name == null) continue;
            TypeDeclaration found = findType(member, jvmName + "$" + new String(member.name), wanted);
            if (found != null) return found;
        }
        return null;
    }

    private static String packagePrefix(CompilationUnitDeclaration unit) {
        if (unit.currentPackage == null || unit.currentPackage.tokens == null) return "";
        StringBuilder prefix = new StringBuilder();
        for (char[] token : unit.currentPackage.tokens) {
            prefix.append(token).append('/');
        }
        return prefix.toString();
    }

    private static DeclaredName declaredName(TypeDeclaration type, Binding binding) {
        if (binding instanceof MethodBinding method) {
            AbstractMethodDeclaration declaration = findMethod(type, method);
            if (declaration != null) {
                return new DeclaredName(declaration.selector,
                        declaration.sourceStart, declaration.declarationSourceStart);
            }
        } else if (binding instanceof FieldBinding field) {
            FieldDeclaration declaration = findField(type, field);
            if (declaration != null) {
                return new DeclaredName(declaration.name,
                        declaration.sourceStart, declaration.declarationSourceStart);
            }
        }
        return new DeclaredName(type.name, type.sourceStart, type.declarationSourceStart);
    }

    /**
     * Overloads are told apart by their parameter type names as spelled in
     * source. Erasure hides type variables (a {@code <T> void m(T)} reads as
     * {@code m(Object)} in the binding), so a unique match on arity alone is
     * still preferred over no match at all.
     */
    private static AbstractMethodDeclaration findMethod(TypeDeclaration type, MethodBinding binding) {
        if (type.methods == null) return null;
        List<String> wanted = parameterTypeNames(binding);
        AbstractMethodDeclaration byArity = null;
        for (AbstractMethodDeclaration method : type.methods) {
            if (method == null || method.isClinit()) continue;
            if (method.isConstructor() != binding.isConstructor()) continue;
            if (!binding.isConstructor() && !Arrays.equals(method.selector, binding.selector)) continue;
            int arity = method.arguments == null ? 0 : method.arguments.length;
            if (arity != wanted.size()) continue;
            if (sourceParameterTypeNames(method).equals(wanted)) return method;
            if (byArity == null) byArity = method;
        }
        return byArity;
    }

    private static FieldDeclaration findField(TypeDeclaration type, FieldBinding binding) {
        if (type.fields == null) return null;
        for (FieldDeclaration field : type.fields) {
            if (field == null || field.name == null || field.name.length == 0) continue;
            if (Arrays.equals(field.name, binding.name)) return field;
        }
        return null;
    }

    private static List<String> parameterTypeNames(MethodBinding binding) {
        TypeBinding[] parameters = binding.parameters;
        if (parameters == null || parameters.length == 0) return List.of();
        List<String> names = new ArrayList<>(parameters.length);
        for (TypeBinding parameter : parameters) {
            names.add(new String(parameter.erasure().sourceName()));
        }
        return names;
    }

    private static List<String> sourceParameterTypeNames(AbstractMethodDeclaration method) {
        Argument[] arguments = method.arguments;
        if (arguments == null || arguments.length == 0) return List.of();
        List<String> names = new ArrayList<>(arguments.length);
        for (Argument argument : arguments) {
            names.add(typeName(argument == null ? null : argument.type));
        }
        return names;
    }

    private static String typeName(TypeReference reference) {
        if (reference == null) return "?";
        char[] last = reference.getLastToken();
        String name = last == null ? "?" : new String(last);
        return name + "[]".repeat(Math.max(0, reference.dimensions()));
    }

    private static Location location(String uri, String text, DeclaredName declared) {
        int start = nameStart(text, declared);
        int end = declared.name() == null ? start : start + declared.name().length;
        return new Location(uri, new Range(
                EcjAnalysisEngine.positionAt(text, start),
                EcjAnalysisEngine.positionAt(text, end)));
    }

    /**
     * ECJ points {@code sourceStart} of a type, method or field declaration
     * at its declared name. Verify that before trusting it, and otherwise
     * search forward from the start of the declaration.
     */
    private static int nameStart(String text, DeclaredName declared) {
        int sourceStart = Math.max(0, declared.sourceStart());
        if (declared.name() == null || declared.name().length == 0) return sourceStart;
        String name = new String(declared.name());
        if (text.startsWith(name, sourceStart)) return sourceStart;
        int found = text.indexOf(name, Math.max(0, declared.declarationStart()));
        return found < 0 ? sourceStart : found;
    }

    /** The name of a declaration, and where to look for it in the source. */
    private record DeclaredName(char[] name, int sourceStart, int declarationStart) {}

    private record ParsedSource(String text, CompilationUnitDeclaration unit) {}

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
