package ch.castleridge.javals.analysis.javac;

import ch.castleridge.javals.classpath.ClasspathOrder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.objectweb.asm.Opcodes;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.TypeEntry;

/**
 * Computes completion candidates - types, fields and methods - for an
 * offset inside an already-{@linkplain WorkspaceCompiler#compile compiled}
 * document.
 *
 * <p>Two completion modes, decided by scanning the raw source text
 * backward from the cursor (rather than trusting tree shape alone, since
 * the code under the cursor is usually mid-edit/erroneous):
 *
 * <ul>
 *   <li><b>Qualified</b> ({@code expr.partial}): resolve the qualifier's
 *       element/type via {@link Trees} and enumerate its members with
 *       {@link Elements#getAllMembers(TypeElement)}.</li>
 *   <li><b>Unqualified</b> (bare identifier): walk the {@link Scope}
 *       chain for locals/parameters/imported names, plus
 *       {@link Elements#getAllMembers(TypeElement)} on every enclosing
 *       class (covers inherited fields/methods that the lexical scope
 *       chain alone does not surface), plus an {@link Index}-wide
 *       simple-name search for types that exist on the classpath but
 *       are not yet imported (offered with an auto-import
 *       {@link CompletionItem#setAdditionalTextEdits(List) additionalTextEdits}).</li>
 * </ul>
 */
public final class CompletionProposer {

    /** Cap on unimported-type suggestions pulled from the index per request. */
    private static final int UNIMPORTED_TYPE_LIMIT = 50;

    /**
     * Synthetic wrapper used by {@link #speculativeReparse}. A bare
     * {@code expr.partial} (or {@code expr.partial(}) with no trailing
     * {@code ;} yet - by far the most common state of the buffer at the
     * moment completion is actually triggered - is not a valid statement
     * on its own (member selects and incomplete calls aren't one of the
     * JLS expression-statement forms), so javac's parser discards it
     * entirely into a bare {@code ErroneousTree} with no recoverable
     * qualifier. Wrapping it as the right-hand side of an assignment
     * turns that into an ordinary (if type-erroring) expression, which
     * javac's semantic error recovery preserves far better than its
     * syntax error recovery.
     */
    private static final String SPECULATIVE_PREFIX = "Object $completion$ = ";

    private CompletionProposer() {}

    public static List<CompletionItem> propose(JavacWorkspaceCompiler.Result compiled,
                                                String source,
                                                long offset,
                                                Index index,
                                                ClasspathOrder classpath) {
        if (compiled == null || source == null) return List.of();
        CompilationUnitTree cu = compiled.cu();
        Trees trees = compiled.trees();
        if (cu == null || trees == null) return List.of();

        int pos = clampOffset(source, offset);
        int identifierStart = pos;
        while (identifierStart > 0 && Character.isJavaIdentifierPart(source.charAt(identifierStart - 1))) {
            identifierStart--;
        }
        String prefix = source.substring(identifierStart, pos);

        int dotOffset = precedingDot(source, identifierStart);
        if (dotOffset >= 0) {
            return proposeQualified(compiled, source, dotOffset, pos, prefix, index, classpath);
        }
        return proposeUnqualified(compiled, cu, trees, identifierStart, pos, prefix, index, classpath);
    }

    private static int clampOffset(String source, long offset) {
        if (offset < 0) return 0;
        if (offset > source.length()) return source.length();
        return (int) offset;
    }

    /** Position of the {@code '.'} immediately preceding {@code identifierStart}, or -1 if there is none. */
    private static int precedingDot(String source, int identifierStart) {
        if (identifierStart <= 0) return -1;
        return source.charAt(identifierStart - 1) == '.' ? identifierStart - 1 : -1;
    }

    // ---- qualified (member-select) completions ----

    private static List<CompletionItem> proposeQualified(JavacWorkspaceCompiler.Result compiled,
                                                          String source,
                                                          int dotOffset,
                                                          int cursor,
                                                          String prefix,
                                                          Index index,
                                                          ClasspathOrder classpath) {
        if (dotOffset <= 0) return List.of();

        List<CompletionItem> resolved = resolveQualifiedMembers(
                compiled.trees(), compiled.cu(), compiled.task().getElements(), dotOffset, prefix);
        if (resolved != null) return resolved;

        // The qualifier didn't resolve against the buffer as typed - most
        // likely javac collapsed the whole (semicolon-less) statement into
        // an ErroneousTree, see SPECULATIVE_PREFIX. Retry once against a
        // locally-patched reparse before giving up.
        JavacWorkspaceCompiler.Result reparsed = speculativeReparse(compiled, source, dotOffset, cursor, index, classpath);
        if (reparsed == null || reparsed.cu() == null || reparsed.trees() == null) return List.of();

        int patchedDotOffset = dotOffset + SPECULATIVE_PREFIX.length();
        List<CompletionItem> viaReparse = resolveQualifiedMembers(
                reparsed.trees(), reparsed.cu(), reparsed.task().getElements(), patchedDotOffset, prefix);
        return viaReparse != null ? viaReparse : List.of();
    }

    /**
     * Resolve the member-select qualifier immediately before {@code dotOffset}
     * and list its accessible members matching {@code prefix}.
     *
     * @return {@code null} as a sentinel meaning "the qualifier itself did
     *         not resolve to anything" (caller may want to retry against a
     *         patched reparse); a non-null (possibly empty) list once the
     *         qualifier genuinely resolved to something (even if that
     *         something has no members, e.g. a primitive type).
     */
    private static List<CompletionItem> resolveQualifiedMembers(Trees trees,
                                                                 CompilationUnitTree cu,
                                                                 Elements elements,
                                                                 int dotOffset,
                                                                 String prefix) {
        TreePath qualifierPath = TreePathLocator.findAt(trees, cu, dotOffset - 1);
        if (qualifierPath == null) return null;

        // A multi-segment qualifier like "java.util" is resolved by trying
        // each prefix as a *class* name first (javac's package-or-type
        // disambiguation), which for an intermediate segment can attribute
        // to a speculative/erroneous ClassSymbol - itself a real
        // TypeElement - before falling back to the package interpretation.
        // Trust an actual package lookup over that ahead of getElement().
        PackageElement pkg = safePackageElement(elements, qualifierPath.getLeaf());
        if (pkg != null) {
            return packageMemberItems(pkg, prefix);
        }

        Scope scope = safeScope(trees, qualifierPath);
        Element qualifierElement = safeElement(trees, qualifierPath);
        if (qualifierElement instanceof PackageElement asPackage) {
            return packageMemberItems(asPackage, prefix);
        }
        if (qualifierElement instanceof TypeElement typeElement) {
            return staticMemberItems(elements, trees, scope, typeElement, prefix);
        }

        TypeMirror qualifierType = safeTypeMirror(trees, qualifierPath);
        if (qualifierElement == null && (qualifierType == null || qualifierType.getKind() == TypeKind.ERROR)) {
            return null;
        }
        if (!(qualifierType instanceof DeclaredType declaredType)) return List.of();
        if (!(declaredType.asElement() instanceof TypeElement typeElement)) return List.of();
        return instanceMemberItems(elements, trees, scope, typeElement, declaredType, prefix);
    }

    /**
     * {@code elements.getPackageElement(qualifierTree.toString())}, guarded
     * against {@code qualifierTree} not actually being a plain dotted name
     * (e.g. a method call or parenthesized expression, whose pretty-printed
     * text wouldn't be a sensible package name to query).
     */
    private static PackageElement safePackageElement(Elements elements, Tree qualifierTree) {
        try {
            String name = qualifierTree.toString();
            if (!isDottedName(name)) return null;
            return elements.getPackageElement(name);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static boolean isDottedName(String s) {
        if (s.isEmpty()) return false;
        boolean atSegmentStart = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (atSegmentStart) return false;
                atSegmentStart = true;
                continue;
            }
            if (atSegmentStart ? !Character.isJavaIdentifierStart(c) : !Character.isJavaIdentifierPart(c)) {
                return false;
            }
            atSegmentStart = false;
        }
        return !atSegmentStart;
    }

    /**
     * Re-parse a locally-patched copy of {@code source} in which the
     * current line is rewritten as {@code SPECULATIVE_PREFIX + <text up
     * to the cursor> + ";"}, discarding whatever (if anything) followed
     * the cursor on that line. Only the current line is touched, so
     * qualifier chains spanning multiple lines (fluent builders, etc.)
     * are a known limitation - out of scope for this fallback.
     */
    private static JavacWorkspaceCompiler.Result speculativeReparse(JavacWorkspaceCompiler.Result original,
                                                                String source,
                                                                int dotOffset,
                                                                int cursor,
                                                                Index index,
                                                                ClasspathOrder classpath) {
        if (index == null || classpath == null) return null;
        CompilationUnitTree cu = original.cu();
        if (cu == null || cu.getSourceFile() == null) return null;

        int lineStart = source.lastIndexOf('\n', Math.max(dotOffset - 1, 0)) + 1;
        int lineEnd = source.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = source.length();
        if (lineStart > dotOffset || cursor > lineEnd) return null;

        String patched = source.substring(0, lineStart)
                + SPECULATIVE_PREFIX
                + source.substring(lineStart, cursor)
                + ";"
                + source.substring(lineEnd);

        try {
            return JavacWorkspaceCompiler.compile(cu.getSourceFile().toUri(), patched, index, classpath);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static List<CompletionItem> packageMemberItems(PackageElement pkg, String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        for (Element enclosed : pkg.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement)) continue;
            String name = enclosed.getSimpleName().toString();
            if (!name.startsWith(prefix)) continue;
            items.add(toCompletionItem(enclosed));
        }
        return items;
    }

    /** {@code TypeName.partial} - static field/method access plus nested type names. */
    private static List<CompletionItem> staticMemberItems(Elements elements,
                                                           Trees trees,
                                                           Scope scope,
                                                           TypeElement typeElement,
                                                           String prefix) {
        DeclaredType declaredType = safeDeclaredType(typeElement);
        List<CompletionItem> items = new ArrayList<>();
        for (Element member : elements.getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            String name = member.getSimpleName().toString();
            if (!name.startsWith(prefix)) continue;
            boolean isTypeMember = isTypeKind(member.getKind());
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            if (!isTypeMember && !isStatic) continue;
            if (scope != null && declaredType != null && !isAccessibleSafe(trees, scope, member, declaredType)) continue;
            items.add(toCompletionItem(member));
        }
        return items;
    }

    /** {@code expr.partial} where {@code expr} has a value type - instance (+ static) member access. */
    private static List<CompletionItem> instanceMemberItems(Elements elements,
                                                             Trees trees,
                                                             Scope scope,
                                                             TypeElement typeElement,
                                                             DeclaredType declaredType,
                                                             String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        for (Element member : elements.getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            String name = member.getSimpleName().toString();
            if (!name.startsWith(prefix)) continue;
            if (scope != null && !isAccessibleSafe(trees, scope, member, declaredType)) continue;
            items.add(toCompletionItem(member));
        }
        return items;
    }

    private static boolean isTypeKind(ElementKind kind) {
        return kind == ElementKind.CLASS || kind == ElementKind.INTERFACE || kind == ElementKind.ENUM
                || kind == ElementKind.RECORD || kind == ElementKind.ANNOTATION_TYPE;
    }

    // ---- unqualified (bare identifier) completions ----

    private static List<CompletionItem> proposeUnqualified(JavacWorkspaceCompiler.Result compiled,
                                                            CompilationUnitTree cu,
                                                            Trees trees,
                                                            int identifierStart,
                                                            int cursor,
                                                            String prefix,
                                                            Index index,
                                                            ClasspathOrder classpath) {
        // Query a position *inside* the typed prefix (its last character)
        // rather than just before it. A non-empty partial identifier with
        // no terminator is "not a statement" on its own, so javac's parser
        // typically collapses the whole thing into a bare ErroneousTree
        // spanning exactly the prefix's own characters; querying just
        // before it (outside that span) would instead land on the
        // enclosing block, whose scope reflects "before any of its
        // statements" and would incorrectly miss locals declared earlier
        // in the same block. Falls back to identifierStart for an empty
        // prefix (bare Ctrl+Space), where this distinction doesn't apply.
        int queryOffset = Math.max(identifierStart, cursor - 1);
        TreePath path = TreePathLocator.findAt(trees, cu, queryOffset);
        if (path == null) {
            path = new TreePath(cu);
        }
        Scope scope = safeScope(trees, path);
        if (scope == null) return List.of();

        Elements elements = compiled.task().getElements();
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<TypeElement> enclosingClasses = new LinkedHashSet<>();

        // Lexically-scoped names: locals, parameters, type parameters, imported
        // (incl. static-imported) names, and directly-declared members of every
        // enclosing class. First occurrence per dedup key wins - inner scopes
        // shadow outer ones, matching Java's own resolution order.
        for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
            for (Element e : s.getLocalElements()) {
                String name = e.getSimpleName().toString();
                if (name.isEmpty() || !name.startsWith(prefix)) continue;
                if (!seen.add(memberDedupKey(e))) continue;
                items.add(toCompletionItem(e));
            }
            TypeElement enclosing = s.getEnclosingClass();
            if (enclosing != null) enclosingClasses.add(enclosing);
        }

        // Members inherited from superclasses/interfaces are not part of the
        // lexical scope chain above (that models nesting, not inheritance), so
        // walk every enclosing class's full member list separately.
        for (TypeElement typeElement : enclosingClasses) {
            DeclaredType declared = safeDeclaredType(typeElement);
            if (declared == null) continue;
            for (Element member : elements.getAllMembers(typeElement)) {
                ElementKind kind = member.getKind();
                if (kind != ElementKind.FIELD && kind != ElementKind.METHOD && kind != ElementKind.ENUM_CONSTANT) continue;
                String name = member.getSimpleName().toString();
                if (!name.startsWith(prefix)) continue;
                if (!isAccessibleSafe(trees, scope, member, declared)) continue;
                if (!seen.add(memberDedupKey(member))) continue;
                items.add(toCompletionItem(member));
            }
        }

        // Types that exist on the classpath but aren't imported yet. Skipped for
        // an empty prefix (bare Ctrl+Space) - that would match the entire index.
        if (index != null && classpath != null && !prefix.isEmpty()) {
            items.addAll(unimportedTypeItems(cu, trees, prefix, index, classpath, seen));
        }
        return items;
    }

    /**
     * Dedup key for shadowing/override resolution: names alone for
     * fields/types/variables (Java only allows one such name to be usable
     * unqualified at a given scope depth), but name **and** erased
     * parameter types for methods/constructors so legitimate overloads
     * are not conflated with each other.
     */
    private static String memberDedupKey(Element e) {
        ElementKind kind = e.getKind();
        String name = e.getSimpleName().toString();
        if ((kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) && e instanceof ExecutableElement ee) {
            StringBuilder key = new StringBuilder(name).append('(');
            for (VariableElement p : ee.getParameters()) {
                key.append(p.asType().toString()).append(',');
            }
            return key.append(')').toString();
        }
        return "n:" + name;
    }

    // ---- unimported-type completions (index-wide) ----

    private static List<CompletionItem> unimportedTypeItems(CompilationUnitTree cu,
                                                             Trees trees,
                                                             String prefix,
                                                             Index index,
                                                             ClasspathOrder classpath,
                                                             Set<String> seenScopeNames) {
        String currentPackage = packageOf(cu);
        Set<String> importedFqcns = importedFqcns(cu);
        Set<String> jvmNamesAdded = new LinkedHashSet<>();
        List<CompletionItem> items = new ArrayList<>();

        // The index is built without knowledge of classpath priority (by
        // design - see Index's own javadoc); a candidate is only offered
        // here if the *current document's* ClasspathOrder actually admits
        // its declaring source, mirroring how IndexClassReader/
        // IndexFileManager control visibility for ordinary compilation.
        for (TypeEntry entry : index.searchTypesBySimpleNamePrefix(prefix, UNIMPORTED_TYPE_LIMIT)) {
            if (!classpath.contains(entry.sourceUri())) continue;
            addUnimportedTypeItem(cu, trees, entry.jvmOwnerName(), entryKind(entry),
                    currentPackage, importedFqcns, seenScopeNames, jvmNamesAdded, items);
            if (items.size() >= UNIMPORTED_TYPE_LIMIT) return items;
        }
        return items;
    }

    private static void addUnimportedTypeItem(CompilationUnitTree cu,
                                              Trees trees,
                                              String jvmOwnerName,
                                              CompletionItemKind kind,
                                              String currentPackage,
                                              Set<String> importedFqcns,
                                              Set<String> seenScopeNames,
                                              Set<String> jvmNamesAdded,
                                              List<CompletionItem> items) {
        if (jvmOwnerName == null || !jvmNamesAdded.add(jvmOwnerName)) return;

        int slash = jvmOwnerName.lastIndexOf('/');
        String simpleName = slash < 0 ? jvmOwnerName : jvmOwnerName.substring(slash + 1);
        if (seenScopeNames.contains("n:" + simpleName)) return; // already offered via scope walk

        String packageName = slash < 0 ? "" : jvmOwnerName.substring(0, slash).replace('/', '.');
        String fqcn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;

        boolean alreadyVisible = packageName.equals(currentPackage)
                || packageName.equals("java.lang")
                || importedFqcns.contains(fqcn)
                || importedFqcns.contains(packageName + ".*");

        CompletionItem item = new CompletionItem(simpleName);
        item.setKind(kind);
        item.setDetail(fqcn);
        item.setInsertText(simpleName);
        item.setSortText("1_" + simpleName);
        if (!alreadyVisible) {
            TextEdit edit = importInsertEdit(cu, trees, fqcn);
            if (edit != null) {
                item.setAdditionalTextEdits(List.of(edit));
            }
        }
        items.add(item);
    }

    private static Set<String> importedFqcns(CompilationUnitTree cu) {
        Set<String> out = new LinkedHashSet<>();
        for (ImportTree imp : cu.getImports()) {
            if (imp.isStatic()) continue;
            out.add(imp.getQualifiedIdentifier().toString());
        }
        return out;
    }

    private static String packageOf(CompilationUnitTree cu) {
        PackageTree pkg = cu.getPackage();
        return pkg == null ? "" : pkg.getPackageName().toString();
    }

    private static CompletionItemKind entryKind(TypeEntry entry) {
        if (entry instanceof SourceTypeEntry source) {
            return switch (source.declKind()) {
                case INTERFACE, ANNOTATION -> CompletionItemKind.Interface;
                case ENUM -> CompletionItemKind.Enum;
                case RECORD -> CompletionItemKind.Struct;
                default -> CompletionItemKind.Class;
            };
        }
        if (entry instanceof ClassFileTypeEntry classFile) {
            int mods = classFile.modifiers();
            if ((mods & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) return CompletionItemKind.Interface;
            if ((mods & Opcodes.ACC_ENUM) != 0) return CompletionItemKind.Enum;
            if ((mods & Opcodes.ACC_RECORD) != 0) return CompletionItemKind.Struct;
        }
        return CompletionItemKind.Class;
    }

    /**
     * Insert {@code import <fqcn>;} on the line after the last existing
     * import (or after the {@code package} statement, or at the very top
     * of the file when neither exists). Assumes the caller has already
     * verified the import is actually needed (not same-package, not
     * {@code java.lang}, not already imported).
     */
    private static TextEdit importInsertEdit(CompilationUnitTree cu, Trees trees, String fqcn) {
        Position pos = importInsertPosition(cu, trees);
        return new TextEdit(new Range(pos, pos), "import " + fqcn + ";\n");
    }

    private static Position importInsertPosition(CompilationUnitTree cu, Trees trees) {
        SourcePositions positions = trees.getSourcePositions();
        List<? extends ImportTree> imports = cu.getImports();
        long endPos;
        if (!imports.isEmpty()) {
            endPos = positions.getEndPosition(cu, imports.get(imports.size() - 1));
        } else {
            PackageTree pkg = cu.getPackage();
            endPos = pkg == null ? -1 : positions.getEndPosition(cu, pkg);
        }
        if (endPos < 0) return new Position(0, 0);
        LineMap lineMap = cu.getLineMap();
        long line = lineMap.getLineNumber(endPos); // 1-based; start of the *next* line is exactly this 0-based line index
        return new Position((int) line, 0);
    }

    // ---- CompletionItem construction ----

    private static CompletionItem toCompletionItem(Element e) {
        String name = e.getSimpleName().toString();
        CompletionItem item = new CompletionItem(name);
        ElementKind kind = e.getKind();
        item.setKind(kindOf(kind));
        item.setSortText("0_" + name);
        switch (kind) {
            case METHOD, CONSTRUCTOR -> {
                if (e instanceof ExecutableElement ee) {
                    item.setDetail(signatureOf(ee));
                }
                item.setInsertText(name + "($0)");
                item.setInsertTextFormat(InsertTextFormat.Snippet);
            }
            case FIELD, ENUM_CONSTANT, PARAMETER, LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> {
                if (e instanceof VariableElement ve) {
                    item.setDetail(ve.asType().toString());
                }
                item.setInsertText(name);
            }
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
                if (e instanceof TypeElement te) {
                    item.setDetail(te.getQualifiedName().toString());
                }
                item.setInsertText(name);
            }
            default -> item.setInsertText(name);
        }
        return item;
    }

    private static String signatureOf(ExecutableElement ee) {
        StringBuilder sb = new StringBuilder();
        if (ee.getKind() != ElementKind.CONSTRUCTOR) {
            sb.append(ee.getReturnType().toString()).append(' ');
        }
        sb.append(ee.getSimpleName()).append('(');
        List<? extends VariableElement> params = ee.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).asType().toString());
        }
        return sb.append(')').toString();
    }

    private static CompletionItemKind kindOf(ElementKind kind) {
        return switch (kind) {
            case FIELD -> CompletionItemKind.Field;
            case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
            case METHOD -> CompletionItemKind.Method;
            case CONSTRUCTOR -> CompletionItemKind.Constructor;
            case CLASS -> CompletionItemKind.Class;
            case INTERFACE -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            case RECORD -> CompletionItemKind.Struct;
            case ANNOTATION_TYPE -> CompletionItemKind.Interface;
            case PARAMETER, LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> CompletionItemKind.Variable;
            case TYPE_PARAMETER -> CompletionItemKind.TypeParameter;
            case PACKAGE -> CompletionItemKind.Module;
            default -> CompletionItemKind.Text;
        };
    }

    // ---- defensive wrappers around javac internals ----

    private static Scope safeScope(Trees trees, TreePath path) {
        try {
            return trees.getScope(path);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static Element safeElement(Trees trees, TreePath path) {
        try {
            return trees.getElement(path);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static TypeMirror safeTypeMirror(Trees trees, TreePath path) {
        try {
            return trees.getTypeMirror(path);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static DeclaredType safeDeclaredType(TypeElement typeElement) {
        try {
            TypeMirror t = typeElement.asType();
            return t instanceof DeclaredType dt ? dt : null;
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static boolean isAccessibleSafe(Trees trees, Scope scope, Element member, DeclaredType type) {
        try {
            return trees.isAccessible(scope, member, type);
        } catch (RuntimeException | Error e) {
            return true;
        }
    }
}
