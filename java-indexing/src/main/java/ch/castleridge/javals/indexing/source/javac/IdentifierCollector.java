package ch.castleridge.javals.indexing.source.javac;

import java.util.HashSet;
import java.util.Set;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import ch.castleridge.javals.indexing.bloom.IdentifierBloomFilter;
import ch.castleridge.javals.indexing.index.Index;

/**
 * Walks a parsed compilation unit and collects every simple identifier
 * name for bloom-filter indexing.
 */
final class IdentifierCollector extends TreeScanner<Void, Void> {

    private final Set<String> names = new HashSet<>();

    static IdentifierBloomFilter collectAndBuild(CompilationUnitTree cu) {
        IdentifierCollector collector = new IdentifierCollector();
        collector.scan(cu, null);
        return IdentifierBloomFilter.create(collector.names);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void p) {
        addName(node.getName());
        return super.visitIdentifier(node, p);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void p) {
        addName(node.getIdentifier());
        return super.visitMemberSelect(node, p);
    }

    @Override
    public Void visitClass(ClassTree node, Void p) {
        addName(node.getSimpleName());
        return super.visitClass(node, p);
    }

    @Override
    public Void visitMethod(MethodTree node, Void p) {
        addName(node.getName());
        return super.visitMethod(node, p);
    }

    @Override
    public Void visitVariable(VariableTree node, Void p) {
        addName(node.getName());
        return super.visitVariable(node, p);
    }

    @Override
    public Void visitTypeParameter(TypeParameterTree node, Void p) {
        addName(node.getName());
        return super.visitTypeParameter(node, p);
    }

    private void addName(CharSequence name) {
        if (name == null) return;
        String s = name.toString();
        if (!s.isEmpty()) names.add(s);
    }
}
