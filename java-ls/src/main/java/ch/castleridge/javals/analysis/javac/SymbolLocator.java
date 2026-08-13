package ch.castleridge.javals.analysis.javac;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ArrayType;

import ch.castleridge.javals.analysis.AttachedSource;
import ch.castleridge.javals.indexing.model.IndexedClassRef;

/**
 * Maps a resolved javac {@link Element} to an LSP {@link Location} that
 * points at the element's declaration in source.
 *
 * <p>The resolution path:
 *
 * <ol>
 *   <li>If {@link Trees#getPath(Element)} returns a non-{@code null} path
 *       inside the open compilation unit, use it directly. This covers
 *       locals, parameters, type parameters, and any same-file
 *       declarations.</li>
 *   <li>Otherwise walk up to the enclosing {@link ClassSymbol}; if its
 *       {@code classfile} is index-backed ({@link IndexClassFileObject}),
 *       recover {@link IndexedClassRef} and parse the companion source via
 *       {@link SourceCache}. Then locate the matching declaration inside that
 *       CU by JVM name (for types), name + parameter-arity (for methods), or
 *       name (for fields/enum constants).</li>
 *   <li>If the enclosing class has no source view in the index (pure
 *       bytecode dependency), return empty - decompiled views are not in
 *       scope here.</li>
 * </ol>
 *
 * <h2>Caveats</h2>
 * <ul>
 *   <li>Method overload disambiguation is at the simple-name level: two
 *       overloads with the same arity and the same simple parameter
 *       names but different package qualifications resolve to whichever
 *       declaration the source-side scan finds first.</li>
 *   <li>{@link Location#getUri()} for sources stored in jars or in the
 *       JRT image returns the original {@code jar:} / {@code jrt:} URI;
 *       LSP clients without a virtual document provider for those
 *       schemes will not be able to open the result.</li>
 * </ul>
 */
public final class SymbolLocator {

    private final SourceCache sourceCache;

    public SymbolLocator(SourceCache sourceCache) {
        this.sourceCache = sourceCache;
    }

    public Optional<Location> locate(Element element,
                                     Trees trees,
                                     CompilationUnitTree openCu,
                                     String openDocUri,
                                     Map<String, String> sourceJarByBinaryJar) {
        if (element == null) return Optional.empty();

        ClassSymbol enclosing = enclosingClass(element);
        if (enclosing != null) {
            JavaFileObject ownerFile = ownerSourceFile(enclosing);
            String ownerUri = ownerFile == null ? "" : ownerFile.toUri().toString();
            boolean external = ownerFile == null
                    || !ownerUri.equals(openDocUri)
                    || isIndexBacked(ownerFile);
            if (external) {
                Optional<Location> indexed = locateThroughIndex(element, sourceJarByBinaryJar, ownerFile);
                if (indexed.isPresent()) return indexed;
            }
        }

        TreePath sameCuPath = trees.getPath(element);
        if (sameCuPath != null && sameCuPath.getCompilationUnit() == openCu) {
            Element atPath = trees.getElement(sameCuPath);
            if (element.equals(atPath)) {
                Tree decl = sameCuPath.getLeaf();
                return rangeFor(decl, openCu, trees.getSourcePositions())
                        .map(r -> new Location(openDocUri, r));
            }
        }

        return locateThroughIndex(element, sourceJarByBinaryJar, null);
    }

    /**
     * Declaration of an indexed type in its attached source. Used by type
     * hierarchy when walking parents/children that are not the open buffer.
     */
    public Optional<Location> locateType(ch.castleridge.javals.indexing.model.TypeEntry entry,
                                         Map<String, String> sourceJarByBinaryJar) {
        if (entry == null) return Optional.empty();
        Optional<String> sourceUriOpt =
                AttachedSource.javaUri(entry.resourceUri(), entry.sourceUri(), sourceJarByBinaryJar);
        if (sourceUriOpt.isEmpty()) return Optional.empty();
        String sourceUri = sourceUriOpt.get();

        Optional<SourceCache.ParsedSource> parsedOpt = sourceCache.parse(sourceUri);
        if (parsedOpt.isEmpty()) return Optional.empty();
        SourceCache.ParsedSource parsed = parsedOpt.get();

        ClassTree owningClass = findClassByJvmName(parsed.cu(), entry.jvmOwnerName());
        if (owningClass == null) return Optional.empty();

        return rangeFor(owningClass, parsed.cu(), parsed.positions())
                .map(r -> new Location(sourceUri, r));
    }

    private static JavaFileObject ownerSourceFile(ClassSymbol enclosing) {
        if (enclosing == null) return null;
        if (enclosing.sourcefile != null) return enclosing.sourcefile;
        return enclosing.classfile;
    }

    private static boolean isIndexBacked(JavaFileObject classfile) {
        return classfile instanceof IndexClassFileObject;
    }

    private Optional<Location> locateThroughIndex(Element element,
                                                  Map<String, String> sourceJarByBinaryJar,
                                                  JavaFileObject ownerFile) {
        ClassSymbol enclosing = enclosingClass(element);
        if (enclosing == null) return Optional.empty();
        JavaFileObject classfile = ownerFile != null ? ownerFile : enclosing.classfile;
        IndexedClassRef ref = classRefFor(classfile, enclosing);
        if (ref == null) return Optional.empty();

        Optional<String> sourceUriOpt =
                AttachedSource.javaUri(ref.resourceUri(), ref.sourceUri(), sourceJarByBinaryJar);
        if (sourceUriOpt.isEmpty()) return Optional.empty();
        String sourceUri = sourceUriOpt.get();

        Optional<SourceCache.ParsedSource> parsedOpt = sourceCache.parse(sourceUri);
        if (parsedOpt.isEmpty()) return Optional.empty();
        SourceCache.ParsedSource parsed = parsedOpt.get();

        ClassTree owningClass = findClassByJvmName(parsed.cu(), ref.jvmOwnerName());
        if (owningClass == null) return Optional.empty();

        Tree decl = pickMember(element, owningClass);
        if (decl == null) return Optional.empty();

        return rangeFor(decl, parsed.cu(), parsed.positions())
                .map(r -> new Location(sourceUri, r));
    }

    private static IndexedClassRef classRefFor(JavaFileObject classfile, ClassSymbol enclosing) {
        IndexedClassRef ref = IndexFileManager.asClassRef(classfile);
        if (ref != null) return ref;
        if (classfile != null && classfile.getKind() == Kind.SOURCE && enclosing != null) {
            String uri = classfile.toUri().toString();
            String jvmName = enclosing.flatname.toString().replace('.', '/');
            return new IndexedClassRef(uri, uri, jvmName);
        }
        return null;
    }

    private static ClassSymbol enclosingClass(Element element) {
        if (element instanceof ClassSymbol cs) return cs;
        Element e = element;
        while (e != null) {
            if (e instanceof ClassSymbol cs) return cs;
            if (e instanceof Symbol s) {
                e = s.owner;
            } else {
                e = e.getEnclosingElement();
            }
        }
        return null;
    }

    private static Tree pickMember(Element element, ClassTree owner) {
        ElementKind kind = element.getKind();
        if (kind.isClass() || kind.isInterface()) {
            return owner;
        }
        if (element instanceof ExecutableElement ee) {
            return pickMethod(ee, owner);
        }
        if (element instanceof VariableElement ve) {
            return pickField(ve, owner);
        }
        return null;
    }

    private static Tree pickMethod(ExecutableElement ee, ClassTree owner) {
        // Source MethodTree names use the class simple name for constructors,
        // not the JVM "<init>" name.
        String wantName = ee.getKind() == ElementKind.CONSTRUCTOR
                ? owner.getSimpleName().toString()
                : ee.getSimpleName().toString();
        int wantArity = ee.getParameters().size();
        List<String> wantParamSig = paramSimpleNames(ee);

        MethodTree byArity = null;
        int arityMatches = 0;
        for (Tree m : owner.getMembers()) {
            if (!(m instanceof MethodTree mt)) continue;
            String name = methodTreeName(mt);
            if (!name.equals(wantName)) continue;
            if (mt.getParameters().size() != wantArity) continue;
            arityMatches++;
            if (byArity == null) byArity = mt;
            if (paramSimpleNamesFromTree(mt).equals(wantParamSig)) {
                return mt;
            }
        }
        return arityMatches == 1 ? byArity : byArity;
    }

    private static String methodTreeName(MethodTree mt) {
        return mt.getName().toString();
    }

    private static Tree pickField(VariableElement ve, ClassTree owner) {
        String name = ve.getSimpleName().toString();
        for (Tree m : owner.getMembers()) {
            if (m instanceof VariableTree vt && vt.getName().toString().equals(name)) {
                return vt;
            }
        }
        return null;
    }

    /**
     * Walk every {@link ClassTree} in {@code cu}, building JVM binary
     * names as we descend (top-level: {@code pkg/Simple}; nested:
     * {@code Outer$Simple}). Returns the tree whose computed name equals
     * {@code jvmName}.
     */
    private static ClassTree findClassByJvmName(CompilationUnitTree cu, String jvmName) {
        if (jvmName == null || jvmName.isEmpty()) return null;
        String packageJvm = cu.getPackageName() == null
                ? ""
                : cu.getPackageName().toString().replace('.', '/');
        Deque<String> stack = new ArrayDeque<>();
        for (Tree t : cu.getTypeDecls()) {
            if (t instanceof ClassTree ct) {
                ClassTree found = walkClass(ct, packageJvm, stack, jvmName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ClassTree walkClass(ClassTree ct,
                                       String packageJvm,
                                       Deque<String> stack,
                                       String wanted) {
        String simple = ct.getSimpleName().toString();
        String localName;
        if (stack.isEmpty()) {
            localName = packageJvm.isEmpty() ? simple : packageJvm + "/" + simple;
        } else {
            localName = stack.peekLast() + "$" + simple;
        }
        if (localName.equals(wanted)) return ct;
        stack.addLast(localName);
        try {
            for (Tree m : ct.getMembers()) {
                if (m instanceof ClassTree inner) {
                    ClassTree found = walkClass(inner, packageJvm, stack, wanted);
                    if (found != null) return found;
                }
            }
        } finally {
            stack.removeLast();
        }
        return null;
    }

    private static Optional<Range> rangeFor(Tree tree, CompilationUnitTree cu, SourcePositions sp) {
        if (tree == null) return Optional.empty();
        long start = sp.getStartPosition(cu, tree);
        long end = sp.getEndPosition(cu, tree);
        if (start < 0) return Optional.empty();
        if (end < 0) end = start;
        LineMap lm = cu.getLineMap();
        return Optional.of(new Range(
                LspPositions.positionAt(lm, start), LspPositions.positionAt(lm, end)));
    }

    // Erasure-level simple-name signature, javac side.
    private static List<String> paramSimpleNames(ExecutableElement ee) {
        List<String> out = new ArrayList<>(ee.getParameters().size());
        for (VariableElement p : ee.getParameters()) {
            if (p instanceof VarSymbol vs) {
                out.add(simpleName(vs.type));
            } else {
                out.add("?");
            }
        }
        return out;
    }

    private static String simpleName(Type t) {
        if (t == null) return "?";
        if (t instanceof ArrayType at) {
            return simpleName(at.elemtype) + "[]";
        }
        if (t.isPrimitiveOrVoid()) {
            return t.toString();
        }
        Symbol tsym = t.tsym;
        if (tsym == null) return "?";
        if (t instanceof Type.TypeVar tv) {
            Type bound = tv.getUpperBound();
            return bound == null ? "Object" : simpleName(bound);
        }
        if (t instanceof Type.WildcardType) {
            return "Object";
        }
        return tsym.name == null ? "?" : tsym.name.toString();
    }

    // Erasure-level simple-name signature, source-tree side.
    private static List<String> paramSimpleNamesFromTree(MethodTree mt) {
        List<String> out = new ArrayList<>(mt.getParameters().size());
        for (VariableTree p : mt.getParameters()) {
            out.add(treeSimpleName(p.getType()));
        }
        return out;
    }

    private static String treeSimpleName(Tree t) {
        if (t == null) return "?";
        if (t instanceof PrimitiveTypeTree pt) {
            return switch (pt.getPrimitiveTypeKind()) {
                case BOOLEAN -> "boolean";
                case BYTE -> "byte";
                case CHAR -> "char";
                case DOUBLE -> "double";
                case FLOAT -> "float";
                case INT -> "int";
                case LONG -> "long";
                case SHORT -> "short";
                case VOID -> "void";
                default -> "?";
            };
        }
        if (t instanceof ArrayTypeTree at) {
            return treeSimpleName(at.getType()) + "[]";
        }
        if (t instanceof ParameterizedTypeTree pt) {
            return treeSimpleName(pt.getType());
        }
        if (t instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (t instanceof MemberSelectTree ms) {
            return ms.getIdentifier().toString();
        }
        if (t instanceof WildcardTree) {
            return "Object";
        }
        return t.toString();
    }
}
