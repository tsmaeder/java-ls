/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementVisitor;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedDeclaration;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;

/**
 * Collects simple identifier names from a parsed Kotlin file for bloom
 * filters.
 */
final class KotlinIdentifierCollector {

    private KotlinIdentifierCollector() {}

    static IdentifierBloomFilter collectAndBuild(KtFile file) {
        Set<String> names = new HashSet<>();
        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(PsiElement element) {
                if (element instanceof KtSimpleNameExpression nameExpr) {
                    add(names, nameExpr.getReferencedName());
                } else if (element instanceof KtNamedDeclaration named) {
                    add(names, named.getName());
                }
                super.visitElement(element);
            }
        });
        return IdentifierBloomFilter.create(names);
    }

    private static void add(Set<String> names, String name) {
        if (name == null || name.isEmpty() || "<no name provided>".equals(name)) return;
        if (name.equals("_")) return;
        names.add(name);
    }
}
