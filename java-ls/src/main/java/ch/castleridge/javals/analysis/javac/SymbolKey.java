package ch.castleridge.javals.analysis.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol.ClassSymbol;

import ch.castleridge.javals.indexing.model.IndexedClassRef;

/**
 * A compilation-independent identity for a symbol, used to match references
 * across independently compiled source files.
 *
 * <p>Cross-file keys embed the declaration's {@code resourceUri} so that
 * classpath shadowing (same JVM binary name from different declarations)
 * does not bleed references across copies.
 *
 * <p>{@link #simpleName()} is the identifier used for bloom-filter candidate
 * selection. For constructors that is the enclosing type's simple name
 * (source ASTs never spell {@code <init>}), while the matching key still
 * uses the JVM {@code <init>} method name.
 */
public final class SymbolKey {

    private final String key;
    private final String simpleName;
    private final boolean fileLocal;

    private SymbolKey(String key, String simpleName, boolean fileLocal) {
        this.key = key;
        this.simpleName = simpleName;
        this.fileLocal = fileLocal;
    }

    public String simpleName() {
        return simpleName;
    }

    public boolean fileLocal() {
        return fileLocal;
    }

    public boolean matches(SymbolKey other) {
        if (other == null) return false;
        if (fileLocal || other.fileLocal) return false;
        return key.equals(other.key);
    }

    public ch.castleridge.javals.analysis.SymbolIdentity toIdentity() {
        Optional<String> origin = Optional.empty();
        if (!fileLocal && key != null) {
            int colon = key.indexOf(':');
            int bar = key.indexOf('|');
            if (colon >= 0 && bar > colon) {
                origin = Optional.of(key.substring(colon + 1, bar));
            }
        }
        return new ch.castleridge.javals.analysis.SymbolIdentity(key, simpleName, fileLocal, origin);
    }

    public static SymbolKey fromIdentity(ch.castleridge.javals.analysis.SymbolIdentity identity) {
        if (identity == null) return null;
        return new SymbolKey(identity.matchKey(), identity.simpleName(), identity.fileLocal());
    }

    /**
     * Build a key for {@code element} in the compilation described by
     * {@code elements}, {@code types}, and {@code trees}. Returns empty
     * when the symbol has no indexed declaration (no {@link IndexedClassRef}
     * and no source path in the current compilation).
     */
    public static Optional<SymbolKey> of(Element element,
                                         Elements elements,
                                         Types types,
                                         Trees trees) {
        if (element == null) return Optional.empty();
        String simpleName = element.getSimpleName().toString();
        if (isFileLocal(element)) {
            return Optional.of(new SymbolKey(null, simpleName, true));
        }
        Optional<String> origin = originResourceUri(element, trees);
        if (origin.isEmpty()) return Optional.empty();

        String originUri = origin.get();
        ElementKind kind = element.getKind();
        if (kind.isClass() || kind.isInterface()) {
            if (!(element instanceof TypeElement type)) return Optional.empty();
            var binaryName = elements.getBinaryName(type);
            if (binaryName == null) return Optional.empty();
            return Optional.of(new SymbolKey(
                    "T:" + originUri + "|" + binaryName, simpleName, false));
        }
        if (element instanceof ExecutableElement ee) {
            TypeElement owner = enclosingType(ee);
            if (owner == null) return Optional.empty();
            var ownerBinary = elements.getBinaryName(owner);
            if (ownerBinary == null) return Optional.empty();
            boolean constructor = ee.getKind() == ElementKind.CONSTRUCTOR;
            // Matching key uses JVM "<init>"; bloom lookup uses the class
            // simple name because source ASTs and call sites never spell
            // "<init>" (e.g. "new Foo()" / "Foo() { }").
            String name = constructor ? "<init>" : ee.getSimpleName().toString();
            String bloomName = constructor
                    ? owner.getSimpleName().toString()
                    : simpleName;
            String descriptor = erasedParamTypes(ee, types);
            return Optional.of(new SymbolKey(
                    "M:" + originUri + "|" + ownerBinary + "#" + name + "(" + descriptor + ")",
                    bloomName,
                    false));
        }
        if (element instanceof VariableElement ve) {
            TypeElement owner = enclosingType(ve);
            if (owner == null) return Optional.empty();
            var ownerBinary = elements.getBinaryName(owner);
            if (ownerBinary == null) return Optional.empty();
            return Optional.of(new SymbolKey(
                    "F:" + originUri + "|" + ownerBinary + "#" + ve.getSimpleName(),
                    simpleName,
                    false));
        }
        return Optional.empty();
    }

    /**
     * Recover the indexed {@code resourceUri} of the declaration that
     * {@code element} resolves to (workspace {@code .java}, dependency
     * {@code .class}, or {@code jrt:} entries).
     */
    public static Optional<String> originResourceUri(Element element, Trees trees) {
        ClassSymbol enclosing = enclosingClass(element);
        if (enclosing == null) return Optional.empty();

        JavaFileObject classfile = enclosing.classfile;
        IndexedClassRef ref = IndexFileManager.asClassRef(classfile);
        if (ref != null) {
            String resourceUri = ref.resourceUri();
            if (resourceUri != null && !resourceUri.isBlank()) {
                return Optional.of(resourceUri);
            }
        }

        if (trees != null) {
            var path = trees.getPath(enclosing);
            if (path != null) {
                var cu = path.getCompilationUnit();
                if (cu != null && cu.getSourceFile() != null) {
                    return Optional.of(cu.getSourceFile().toUri().toString());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isFileLocal(Element element) {
        return switch (element.getKind()) {
            case LOCAL_VARIABLE, PARAMETER, TYPE_PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> true;
            default -> false;
        };
    }

    private static ClassSymbol enclosingClass(Element element) {
        if (element instanceof ClassSymbol cs) return cs;
        Element e = element;
        while (e != null) {
            if (e instanceof ClassSymbol cs) return cs;
            e = e.getEnclosingElement();
        }
        return null;
    }

    private static TypeElement enclosingType(Element element) {
        Element e = element.getEnclosingElement();
        while (e != null) {
            if (e instanceof TypeElement te) return te;
            e = e.getEnclosingElement();
        }
        return null;
    }

    private static String erasedParamTypes(ExecutableElement ee, Types types) {
        List<String> parts = new ArrayList<>(ee.getParameters().size());
        for (VariableElement p : ee.getParameters()) {
            TypeMirror erased = types.erasure(p.asType());
            parts.add(erased == null ? "?" : erased.toString());
        }
        return String.join(",", parts);
    }
}
