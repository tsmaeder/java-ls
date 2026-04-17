package ch.castleridge.javals.indexing.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per compilation-unit hints captured by the source indexer and consumed by
 * the class reader when it materialises a {@link TypeEntry} into a javac
 * {@code ClassSymbol}. Every {@link TypeRef.Unresolved} emitted by the
 * source indexer is resolved against the hints of its enclosing type using
 * the JLS ordering:
 *
 * <ol>
 *   <li>Declared in the same compilation unit ({@link #siblingSimpleNames()}).</li>
 *   <li>Single-type imports ({@link #singleTypeImports()}).</li>
 *   <li>Same-package types (derived from {@link #sourcePackage()}).</li>
 *   <li>On-demand ({@code .*}) imports ({@link #onDemandImports()}).</li>
 *   <li>Implicit {@code java.lang} import.</li>
 * </ol>
 *
 * <p>All fields use JVM binary form (slash-delimited, never dot-delimited),
 * except {@link #siblingSimpleNames()} which is made of Java simple names.
 */
public record SourceResolutionHints(
        String sourcePackage,
        Map<String, String> singleTypeImports,
        List<String> onDemandImports,
        Set<String> siblingSimpleNames) {

    public SourceResolutionHints {
        sourcePackage = sourcePackage == null ? "" : sourcePackage;
        singleTypeImports = singleTypeImports == null ? Map.of() : Map.copyOf(singleTypeImports);
        onDemandImports = onDemandImports == null ? List.of() : List.copyOf(onDemandImports);
        siblingSimpleNames = siblingSimpleNames == null ? Set.of() : Set.copyOf(siblingSimpleNames);
    }
}
