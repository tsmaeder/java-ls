/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import java.util.Locale;

import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;

import ch.castleridge.javals.analysis.SourceText;

/**
 * Diet-parses attached {@code .java} with ECJ and lowers it. Used by
 * {@link ch.castleridge.javals.analysis.AstDeclarationLocator}.
 */
public final class EcjDietSources {

    private EcjDietSources() {}

    public static ch.castleridge.javals.ast.CompilationUnit lower(String uri) {
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
        String fileName = fileNameOf(uri);
        ICompilationUnit input = new CompilationUnit(text.toCharArray(), fileName, "UTF-8");
        CompilationResult result = new CompilationResult(input, 0, 1, options.maxProblemsPerUnit);
        try {
            CompilationUnitDeclaration unit = parser.dietParse(input, result);
            if (unit == null) return null;
            return EcjAstLowerer.diet(unit, uri, text);
        } catch (RuntimeException | StackOverflowError failure) {
            return null;
        }
    }

    private static String fileNameOf(String uri) {
        String name = uri;
        int bang = name.lastIndexOf("!/");
        if (bang >= 0) name = name.substring(bang + 2);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return name.isBlank() ? "Attached.java" : name;
    }
}
