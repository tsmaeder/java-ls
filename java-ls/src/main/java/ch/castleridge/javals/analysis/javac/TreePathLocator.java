package ch.castleridge.javals.analysis.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/**
 * Finds the deepest {@link Tree} in a compilation unit whose source range
 * contains a given character offset, then returns its {@link TreePath}.
 * Used by the LSP request handlers to map a cursor position back onto a
 * javac AST node.
 */
public final class TreePathLocator {

    private TreePathLocator() {}

    /**
     * Return a {@link TreePath} for the deepest tree in {@code cu} that
     * spans {@code offset}, or {@code null} if no such tree exists.
     */
    public static TreePath findAt(Trees trees, CompilationUnitTree cu, long offset) {
        if (cu == null) return null;
        SourcePositions sp = trees.getSourcePositions();
        Finder finder = new Finder(cu, sp, offset);
        finder.scan(cu, null);
        if (finder.deepest == null) return null;
        return trees.getPath(cu, finder.deepest);
    }

    private static final class Finder extends TreeScanner<Void, Void> {
        private final CompilationUnitTree cu;
        private final SourcePositions sp;
        private final long offset;
        private int currentDepth;
        private int deepestDepth = -1;
        Tree deepest;

        Finder(CompilationUnitTree cu, SourcePositions sp, long offset) {
            this.cu = cu;
            this.sp = sp;
            this.offset = offset;
        }

        @Override
        public Void scan(Tree tree, Void p) {
            if (tree == null) return null;
            boolean isCu = tree.getKind() == Tree.Kind.COMPILATION_UNIT;
            if (!isCu) {
                long start = sp.getStartPosition(cu, tree);
                long end = sp.getEndPosition(cu, tree);
                if (start < 0 || end < 0 || offset < start || offset >= end) {
                    return null;
                }
            }
            currentDepth++;
            try {
                if (currentDepth > deepestDepth) {
                    deepestDepth = currentDepth;
                    deepest = tree;
                }
                super.scan(tree, p);
            } finally {
                currentDepth--;
            }
            return null;
        }
    }
}
