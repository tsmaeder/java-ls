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
package ch.castleridge.javals.indexing.source.ecj;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;

/**
 * Walks a parsed ECJ compilation unit and collects every simple identifier
 * name for bloom-filter indexing.
 */
final class EcjIdentifierCollector extends ASTVisitor {

    private final Set<String> names = new HashSet<>();

    static IdentifierBloomFilter collectAndBuild(CompilationUnitDeclaration unit) {
        EcjIdentifierCollector collector = new EcjIdentifierCollector();
        unit.traverse(collector, (CompilationUnitScope) null);
        return IdentifierBloomFilter.create(collector.names);
    }

    @Override
    public boolean visit(TypeDeclaration typeDeclaration, CompilationUnitScope scope) {
        addName(typeDeclaration.name);
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration typeDeclaration, ClassScope scope) {
        addName(typeDeclaration.name);
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration typeDeclaration, BlockScope scope) {
        addName(typeDeclaration.name);
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration methodDeclaration, ClassScope scope) {
        addName(methodDeclaration.selector);
        return true;
    }

    @Override
    public boolean visit(ConstructorDeclaration constructorDeclaration, ClassScope scope) {
        addName(constructorDeclaration.selector);
        return true;
    }

    @Override
    public boolean visit(FieldDeclaration fieldDeclaration, MethodScope scope) {
        addName(fieldDeclaration.name);
        return true;
    }

    @Override
    public boolean visit(Argument argument, BlockScope scope) {
        addName(argument.name);
        return true;
    }

    @Override
    public boolean visit(Argument argument, ClassScope scope) {
        addName(argument.name);
        return true;
    }

    @Override
    public boolean visit(TypeParameter typeParameter, ClassScope scope) {
        addName(typeParameter.name);
        return true;
    }

    @Override
    public boolean visit(TypeParameter typeParameter, BlockScope scope) {
        addName(typeParameter.name);
        return true;
    }

    @Override
    public boolean visit(SingleNameReference singleNameReference, BlockScope scope) {
        addName(singleNameReference.token);
        return true;
    }

    @Override
    public boolean visit(SingleNameReference singleNameReference, ClassScope scope) {
        addName(singleNameReference.token);
        return true;
    }

    @Override
    public boolean visit(QualifiedNameReference qualifiedNameReference, BlockScope scope) {
        addTokens(qualifiedNameReference.tokens);
        return true;
    }

    @Override
    public boolean visit(QualifiedNameReference qualifiedNameReference, ClassScope scope) {
        addTokens(qualifiedNameReference.tokens);
        return true;
    }

    @Override
    public boolean visit(FieldReference fieldReference, BlockScope scope) {
        addName(fieldReference.token);
        return true;
    }

    @Override
    public boolean visit(FieldReference fieldReference, ClassScope scope) {
        addName(fieldReference.token);
        return true;
    }

    @Override
    public boolean visit(MessageSend messageSend, BlockScope scope) {
        addName(messageSend.selector);
        return true;
    }

    @Override
    public boolean visit(ReferenceExpression referenceExpression, BlockScope scope) {
        addName(referenceExpression.selector);
        return true;
    }

    @Override
    public boolean visit(SingleTypeReference singleTypeReference, BlockScope scope) {
        addName(singleTypeReference.token);
        return true;
    }

    @Override
    public boolean visit(SingleTypeReference singleTypeReference, ClassScope scope) {
        addName(singleTypeReference.token);
        return true;
    }

    @Override
    public boolean visit(QualifiedTypeReference qualifiedTypeReference, BlockScope scope) {
        addTokens(qualifiedTypeReference.tokens);
        return true;
    }

    @Override
    public boolean visit(QualifiedTypeReference qualifiedTypeReference, ClassScope scope) {
        addTokens(qualifiedTypeReference.tokens);
        return true;
    }

    @Override
    public boolean visit(ParameterizedSingleTypeReference typeReference, BlockScope scope) {
        addName(typeReference.token);
        return true;
    }

    @Override
    public boolean visit(ParameterizedSingleTypeReference typeReference, ClassScope scope) {
        addName(typeReference.token);
        return true;
    }

    @Override
    public boolean visit(ParameterizedQualifiedTypeReference typeReference, BlockScope scope) {
        addTokens(typeReference.tokens);
        return true;
    }

    @Override
    public boolean visit(ParameterizedQualifiedTypeReference typeReference, ClassScope scope) {
        addTokens(typeReference.tokens);
        return true;
    }

    @Override
    public boolean visit(ImportReference importRef, CompilationUnitScope scope) {
        addTokens(importRef.tokens);
        return true;
    }

    private void addTokens(char[][] tokens) {
        if (tokens == null) return;
        for (char[] token : tokens) {
            addName(token);
        }
    }

    private void addName(char[] name) {
        if (name == null || name.length == 0) return;
        names.add(new String(name));
    }
}
