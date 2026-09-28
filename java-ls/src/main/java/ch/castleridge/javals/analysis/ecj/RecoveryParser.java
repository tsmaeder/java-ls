/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AbstractVariableDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalToken;
import org.eclipse.jdt.internal.compiler.parser.diagnose.DiagnoseParser;
import org.eclipse.jdt.internal.compiler.parser.diagnose.RangeUtil;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

/**
 * ECJ 3.46 statement recovery, without the two passes that dominate opening a
 * large generated file.
 *
 * <p>With statement recovery on, diet parse runs {@code DiagnoseParser} over
 * every method whose signature did not parse. That search is a scope trial per
 * token, and one oversized method body makes it the whole open. The same flag
 * then walks every type found inside the recovered method and re-parses each of
 * its methods, which on a protobuf file is the rest of the compilation unit.
 *
 * <p>Diet parse still diagnoses the unit with method bodies skipped, and a
 * broken method is still reparsed once. Nested types inside that method are
 * not walked again.
 */
final class RecoveryParser extends Parser {
    RecoveryParser(ProblemReporter problemReporter, boolean optimizeStringLiterals) {
        super(problemReporter, optimizeStringLiterals);
    }

    /**
     * Diet parse already diagnosed the unit. Do not diagnose each skipped
     * method again; that is {@code reportSyntaxErrorsForSkippedMethod}.
     */
    @Override
    protected void reportSyntaxErrors(boolean isDietParse, TerminalToken oldFirstToken) {
        if (this.referenceContext instanceof MethodDeclaration methodDeclaration
                && (methodDeclaration.bits & ASTNode.ErrorInSignature) != 0) {
            return;
        }
        if (!isDietParse) {
            super.reportSyntaxErrors(false, oldFirstToken);
            return;
        }
        this.compilationUnit.compilationResult.lineSeparatorPositions = this.scanner.getLineEnds();
        this.scanner.recordLineSeparator = false;

        int start = this.scanner.initialPosition;
        int end = this.scanner.eofPosition == Integer.MAX_VALUE
                ? this.scanner.eofPosition
                : this.scanner.eofPosition - 1;
        TypeDeclaration[] types = this.compilationUnit.types;
        int[][] intervalToSkip = RangeUtil.computeDietRange(types);
        DiagnoseParser diagnoseParser = new DiagnoseParser(
                this, oldFirstToken, start, end,
                intervalToSkip[0], intervalToSkip[1], intervalToSkip[2], this.options);
        diagnoseParser.diagnoseParse(false);
        this.scanner.resetTo(start, end);
    }

    /**
     * Reparse the broken method body once. ECJ then walks every type recorded
     * in that body and reparses its methods; those types are the following
     * classes in the file when the body span runs on.
     */
    @Override
    protected void recoverStatements() {
        if (this.recoveryScanner == null) return;
        if (this.referenceContext instanceof AbstractMethodDeclaration method) {
            reparse(method, method.bodyStart, method.bodyEnd);
        } else if (this.referenceContext instanceof TypeDeclaration type && type.fields != null) {
            for (FieldDeclaration field : type.fields) {
                if (field.getKind() != AbstractVariableDeclaration.INITIALIZER) continue;
                Initializer initializer = (Initializer) field;
                if (initializer.block == null) continue;
                reparse(type, initializer.bodyStart, initializer.bodyEnd);
            }
        }
    }

    private void reparse(ReferenceContext context, int start, int end) {
        if (start < 0 || end < start) return;
        ReferenceContext oldContext = this.referenceContext;
        Scanner oldScanner = this.scanner;
        this.recoveryScanner.resetTo(start, end);
        this.scanner = this.recoveryScanner;
        this.referenceContext = context;
        try {
            parseStatements(context, start, end, null, this.compilationUnit);
        } finally {
            this.scanner = oldScanner;
            this.referenceContext = oldContext;
        }
    }
}
