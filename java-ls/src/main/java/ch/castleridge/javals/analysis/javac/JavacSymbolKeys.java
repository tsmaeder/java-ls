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
package ch.castleridge.javals.analysis.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol.ClassSymbol;

import ch.castleridge.javals.analysis.AttachedSource;
import ch.castleridge.javals.analysis.FileUris;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.IndexedClassRef;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Builds {@link SymbolKey} values from javac {@link Element}s.
 *
 * <p>Cross-file keys embed the declaration's {@code resourceUri} so that
 * classpath shadowing (same JVM binary name from different declarations)
 * does not bleed references across copies.
 *
 * <p>{@link SymbolKey#simpleName()} is the identifier used for bloom-filter
 * candidate selection. For constructors that is the enclosing type's simple
 * name (source ASTs never spell {@code <init>}), while the matching key still
 * uses the JVM {@code <init>} method name.
 */
public final class JavacSymbolKeys {

    private JavacSymbolKeys() {}

    /**
     * Build a key for {@code element} in the compilation described by
     * {@code elements}, {@code types}, and {@code trees}. Returns empty
     * when the symbol has no indexed declaration (no {@link IndexedClassRef}
     * and no source path in the current compilation).
     *
     * <p>When this compilation declares the type, the origin is the classpath
     * winner's {@code resourceUri} if that entry is the same file as the
     * buffer, or the indexed {@code .class} when the buffer is that entry's
     * attached source. The stored string is the index spelling, not the editor URI.
     */
    public static Optional<SymbolKey> of(Element element,
                                         Elements elements,
                                         Types types,
                                         Trees trees,
                                         Index index,
                                         ClasspathOrder classpath,
                                         Map<String, String> sourceJarByBinaryJar) {
        if (element == null) return Optional.empty();
        String simpleName = element.getSimpleName().toString();
        if (isFileLocal(element)) {
            return Optional.of(SymbolKey.local(simpleName));
        }
        Optional<String> origin = originResourceUri(element, trees, index, classpath, sourceJarByBinaryJar);
        if (origin.isEmpty()) return Optional.empty();

        String originUri = origin.get();
        ElementKind kind = element.getKind();
        if (kind.isClass() || kind.isInterface()) {
            if (!(element instanceof TypeElement type)) return Optional.empty();
            var binaryName = elements.getBinaryName(type);
            if (binaryName == null) return Optional.empty();
            return Optional.of(SymbolKey.of(
                    "T:" + originUri + "|" + binaryName, simpleName, originUri));
        }
        if (element instanceof ExecutableElement ee) {
            TypeElement owner = enclosingType(ee);
            if (owner == null) return Optional.empty();
            var ownerBinary = elements.getBinaryName(owner);
            if (ownerBinary == null) return Optional.empty();
            boolean constructor = ee.getKind() == ElementKind.CONSTRUCTOR;
            // Matching key uses JVM "<init>"; bloom lookup uses the class
            // simple name because source ASTs and call sites never spell
            // "<init>" (e.g. "new Foo()" / "Foo() { }").
            String name = constructor ? "<init>" : ee.getSimpleName().toString();
            String bloomName = constructor
                    ? owner.getSimpleName().toString()
                    : simpleName;
            String descriptor = erasedParamTypes(ee, types);
            return Optional.of(SymbolKey.of(
                    "M:" + originUri + "|" + ownerBinary + "#" + name + "(" + descriptor + ")",
                    bloomName,
                    originUri));
        }
        if (element instanceof VariableElement ve) {
            TypeElement owner = enclosingType(ve);
            if (owner == null) return Optional.empty();
            var ownerBinary = elements.getBinaryName(owner);
            if (ownerBinary == null) return Optional.empty();
            return Optional.of(SymbolKey.of(
                    "F:" + originUri + "|" + ownerBinary + "#" + ve.getSimpleName(),
                    simpleName,
                    originUri));
        }
        return Optional.empty();
    }

    /**
     * Recover the indexed {@code resourceUri} of the declaration that
     * {@code element} resolves to (workspace {@code .java}, dependency
     * {@code .class}, or {@code jrt:} entries).
     *
     * <p>A type loaded from the index keeps that entry's URI. A type declared
     * in this compilation uses the classpath winner's URI when the winner is
     * this file, or when this file is the attached source of that {@code .class},
     * so an editor URI that differs only in spelling (Windows drive-letter case)
     * still matches uses. A shadowed duplicate that this unit declares keeps
     * the compilation URI.
     */
    public static Optional<String> originResourceUri(Element element,
                                                      Trees trees,
                                                      Index index,
                                                      ClasspathOrder classpath,
                                                      Map<String, String> sourceJarByBinaryJar) {
        ClassSymbol enclosing = enclosingClass(element);
        if (enclosing == null) return Optional.empty();

        JavaFileObject classfile = enclosing.classfile;
        IndexedClassRef ref = IndexFileManager.asClassRef(classfile);
        if (ref != null) {
            String resourceUri = ref.resourceUri();
            if (resourceUri != null && !resourceUri.isBlank()) {
                return Optional.of(resourceUri);
            }
        }

        String compilationUri = compilationUri(enclosing, trees);
        TypeEntry entry = indexedEntry(enclosing, index, classpath);
        String indexed = indexedOrigin(entry);
        boolean declaredHere = compilationUri != null;
        if (indexed != null && (!declaredHere || sameDeclaration(
                compilationUri, indexed, entry, sourceJarByBinaryJar))) {
            return Optional.of(indexed);
        }
        if (declaredHere) return Optional.of(compilationUri);
        return Optional.empty();
    }

    private static boolean sameDeclaration(String compilationUri,
                                           String indexed,
                                           TypeEntry entry,
                                           Map<String, String> sourceJarByBinaryJar) {
        if (FileUris.sameFile(compilationUri, indexed)) return true;
        if (entry == null) return false;
        return AttachedSource.javaUri(indexed, entry.sourceUri(), sourceJarByBinaryJar)
                .filter(javaUri -> FileUris.sameFile(compilationUri, javaUri))
                .isPresent();
    }

    private static String compilationUri(ClassSymbol enclosing, Trees trees) {
        if (trees == null || enclosing == null) return null;
        var path = trees.getPath(enclosing);
        if (path == null) return null;
        CompilationUnitTree cu = path.getCompilationUnit();
        if (cu == null || cu.getSourceFile() == null) return null;
        return cu.getSourceFile().toUri().toString();
    }

    private static TypeEntry indexedEntry(ClassSymbol enclosing, Index index, ClasspathOrder classpath) {
        if (enclosing == null || index == null || classpath == null) return null;
        String jvmName = enclosing.flatName().toString().replace('.', '/');
        return classpath.pick(index.getAll(jvmName), TypeEntry::sourceUri);
    }

    private static String indexedOrigin(TypeEntry entry) {
        if (entry == null) return null;
        String resourceUri = entry.resourceUri();
        if (resourceUri == null || resourceUri.isBlank()) return null;
        return resourceUri;
    }

    private static boolean isFileLocal(Element element) {
        return switch (element.getKind()) {
            case LOCAL_VARIABLE, PARAMETER, TYPE_PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> true;
            default -> false;
        };
    }

    private static ClassSymbol enclosingClass(Element element) {
        if (element instanceof ClassSymbol cs) return cs;
        Element e = element;
        while (e != null) {
            if (e instanceof ClassSymbol cs) return cs;
            e = e.getEnclosingElement();
        }
        return null;
    }

    private static TypeElement enclosingType(Element element) {
        Element e = element.getEnclosingElement();
        while (e != null) {
            if (e instanceof TypeElement te) return te;
            e = e.getEnclosingElement();
        }
        return null;
    }

    private static String erasedParamTypes(ExecutableElement ee, Types types) {
        List<String> parts = new ArrayList<>(ee.getParameters().size());
        for (VariableElement p : ee.getParameters()) {
            TypeMirror erased = types.erasure(p.asType());
            parts.add(erased == null ? "?" : erased.toString());
        }
        return String.join(",", parts);
    }
}
