package ch.castleridge.javals.javac;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * Walks an analyzed compilation unit and collects LSP {@link Location}s for
 * usages that resolve to the given {@link SymbolKey}.
 */
public final class ReferenceFinder {

    private ReferenceFinder() {}

    public static Set<Location> findReferences(CompilationUnitTree cu,
                                               Trees trees,
                                               Elements elements,
                                               Types types,
                                               String docUri,
                                               SymbolKey targetKey,
                                               Element targetElement) {
        if (cu == null || trees == null || targetKey == null) {
            return Set.of();
        }
        ReferenceScanner scanner = new ReferenceScanner(
                cu, trees, elements, types, docUri, targetKey, targetElement);
        scanner.scan(cu, null);
        return scanner.results;
    }

    private static final class ReferenceScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree cu;
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final String docUri;
        private final SymbolKey targetKey;
        private final Element targetElement;
        private final SourcePositions positions;
        private final Set<Location> results = new LinkedHashSet<>();

        ReferenceScanner(CompilationUnitTree cu,
                         Trees trees,
                         Elements elements,
                         Types types,
                         String docUri,
                         SymbolKey targetKey,
                         Element targetElement) {
            this.cu = cu;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.docUri = docUri;
            this.targetKey = targetKey;
            this.targetElement = targetElement;
            this.positions = trees.getSourcePositions();
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            considerReference(node);
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            considerReference(node);
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            // Identifier inside "new Foo()" binds to the type; the constructor
            // is on the NewClassTree itself.
            considerReference(constructorNameTree(node));
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            // Regular calls are already covered via Identifier/MemberSelect.
            // Explicit this()/super() constructor calls bind on the invocation.
            Element element = trees.getElement(getCurrentPath());
            if (element != null && element.getKind() == ElementKind.CONSTRUCTOR) {
                considerReference(node.getMethodSelect());
            }
            return super.visitMethodInvocation(node, unused);
        }

        private void considerReference(Tree highlightNode) {
            TreePath path = getCurrentPath();
            Element element = trees.getElement(path);
            if (element == null) return;

            if (targetKey.fileLocal()) {
                if (targetElement != null && element.equals(targetElement)) {
                    nameRange(highlightNode).ifPresent(r -> results.add(new Location(docUri, r)));
                }
                return;
            }

            SymbolKey usageKey = SymbolKey.of(element, elements, types, trees).orElse(null);
            if (usageKey != null && targetKey.matches(usageKey)) {
                nameRange(highlightNode).ifPresent(r -> results.add(new Location(docUri, r)));
            }
        }

        private static Tree constructorNameTree(NewClassTree node) {
            Tree id = node.getIdentifier();
            if (id instanceof ParameterizedTypeTree parameterized) {
                id = parameterized.getType();
            }
            if (id instanceof MemberSelectTree ms) {
                return ms;
            }
            return id != null ? id : node;
        }

        private java.util.Optional<Range> nameRange(Tree node) {
            if (node instanceof IdentifierTree id) {
                return rangeFor(id);
            }
            if (node instanceof MemberSelectTree ms) {
                long end = positions.getEndPosition(cu, ms);
                if (end < 0) return java.util.Optional.empty();
                String name = ms.getIdentifier().toString();
                long start = end - name.length();
                if (start < 0) start = 0;
                LineMap lm = cu.getLineMap();
                return java.util.Optional.of(new Range(positionAt(lm, start), positionAt(lm, end)));
            }
            return java.util.Optional.empty();
        }

        private java.util.Optional<Range> rangeFor(Tree tree) {
            long start = positions.getStartPosition(cu, tree);
            long end = positions.getEndPosition(cu, tree);
            if (start < 0) return java.util.Optional.empty();
            if (end < 0) end = start;
            LineMap lm = cu.getLineMap();
            return java.util.Optional.of(new Range(positionAt(lm, start), positionAt(lm, end)));
        }

        private static Position positionAt(LineMap lm, long offset) {
            long line = lm.getLineNumber(offset);
            long col = lm.getColumnNumber(offset);
            return new Position(toIntClamped(line - 1), toIntClamped(col - 1));
        }

        private static int toIntClamped(long v) {
            if (v < 0) return 0;
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) v;
        }
    }
}
