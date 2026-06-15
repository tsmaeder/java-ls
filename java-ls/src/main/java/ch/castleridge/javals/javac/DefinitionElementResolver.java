package ch.castleridge.javals.javac;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

/**
 * Resolves the {@link Element} targeted by an LSP go-to-definition request.
 *
 * <p>javac binds type names inside {@code import} declarations (e.g.
 * {@code import java.util.Base64.Encoder}) but not static members in
 * {@code import static} declarations (e.g.
 * {@code import static java.time.format.DateTimeFormatter.ISO_INSTANT}).
 * For the latter, walking {@link Trees#getElement(TreePath)} up the
 * path eventually lands on the compilation unit's package. This helper
 * fills that gap for the imported static member name.
 */
public final class DefinitionElementResolver {

    private DefinitionElementResolver() {}

    public static Element resolve(Trees trees, TreePath path) {
        Element fromStaticImport = resolveStaticImportMember(trees, path);
        if (fromStaticImport != null) return fromStaticImport;
        return elementAlongPath(trees, path);
    }

    /**
     * Walk {@code path} toward the root, returning the first bound
     * element that is not a package or module.
     */
    private static Element elementAlongPath(Trees trees, TreePath path) {
        TreePath cur = path;
        while (cur != null) {
            Element e = trees.getElement(cur);
            if (e != null && e.getKind() != ElementKind.PACKAGE && e.getKind() != ElementKind.MODULE) {
                return e;
            }
            cur = cur.getParentPath();
        }
        return null;
    }

    /**
     * When the cursor sits on the imported member in
     * {@code import static Owner.member}, resolve {@code member} against
     * {@code Owner}.
     */
    private static Element resolveStaticImportMember(Trees trees, TreePath path) {
        TreePath importPath = enclosingImport(path);
        if (importPath == null) return null;

        ImportTree imp = (ImportTree) importPath.getLeaf();
        if (!imp.isStatic()) return null;

        Tree qualId = imp.getQualifiedIdentifier();
        if (!(qualId instanceof MemberSelectTree staticImport)) return null;

        if (!cursorOnImportedMember(path, staticImport)) return null;

        TreePath qualPath = new TreePath(importPath, qualId);
        Element owner = elementAlongPath(trees, new TreePath(qualPath, staticImport.getExpression()));
        if (!(owner instanceof TypeElement type)) return null;

        CharSequence memberName = staticImport.getIdentifier();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getSimpleName().contentEquals(memberName)) {
                return enclosed;
            }
        }
        return null;
    }

    private static TreePath enclosingImport(TreePath path) {
        TreePath cur = path;
        while (cur != null) {
            if (cur.getLeaf() instanceof ImportTree) return cur;
            cur = cur.getParentPath();
        }
        return null;
    }

    /**
     * True when {@code path} points at the rightmost identifier of
     * {@code staticImport} (the imported static member name).
     */
    private static boolean cursorOnImportedMember(TreePath path, MemberSelectTree staticImport) {
        Tree leaf = path.getLeaf();
        if (leaf instanceof IdentifierTree id) {
            return id.getName().contentEquals(staticImport.getIdentifier());
        }
        if (leaf == staticImport) {
            return true;
        }
        return false;
    }
}
