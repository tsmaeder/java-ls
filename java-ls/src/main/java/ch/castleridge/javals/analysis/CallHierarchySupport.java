/**
 * Copyright 2026 by Castle Ridge Software
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

import com.google.gson.JsonElement;

import ch.castleridge.javals.ast.AstVisitor;
import ch.castleridge.javals.ast.Block;
import ch.castleridge.javals.ast.CallExpr;
import ch.castleridge.javals.ast.CompactConstructorDecl;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConstructorCallStmt;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.MemberRefExpr;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.MethodSymbol;
import ch.castleridge.javals.ast.NewExpr;
import ch.castleridge.javals.ast.Node;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.SourceRange;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.SymbolKey;
import ch.castleridge.javals.ast.TypeDecl;

/**
 * Call hierarchy over attributed AST: prepare items from {@link MethodSymbol},
 * incoming call sites grouped by enclosing callable, and outgoing callees from
 * a method body (excluding nested types/methods).
 */
public final class CallHierarchySupport {

    private CallHierarchySupport() {}

    public static Optional<CallHierarchyItem> itemFor(
            MethodSymbol method, Location location) {
        if (method == null || location == null) return Optional.empty();
        SymbolKey key = method.key();
        if (key == null || key.fileLocal() || key.matchKey() == null
                || !key.matchKey().startsWith("M:")) {
            return Optional.empty();
        }
        Range range = location.getRange();
        if (range == null) {
            range = new Range(new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(0, 0));
        }
        String name = displayName(method);
        String detail = detailOf(method);
        SymbolKind kind = method.constructor() || "<init>".equals(method.name())
                ? SymbolKind.Constructor
                : SymbolKind.Method;
        CallHierarchyItem item = new CallHierarchyItem(
                name, kind, location.getUri(), range, range);
        item.setDetail(detail);
        item.setData(key.matchKey());
        return Optional.of(item);
    }

    /**
     * Recover a cross-file method {@link SymbolKey} from {@code item.data}
     * (string or gson {@link JsonElement} after a client round-trip).
     */
    public static Optional<SymbolKey> keyFromItem(CallHierarchyItem item) {
        if (item == null) return Optional.empty();
        return keyFromData(item.getData());
    }

    public static Optional<SymbolKey> keyFromData(Object data) {
        String matchKey = matchKeyString(data);
        if (matchKey == null || !matchKey.startsWith("M:")) return Optional.empty();
        int bar = matchKey.indexOf('|');
        if (bar < 0 || bar + 1 >= matchKey.length()) return Optional.empty();
        String origin = matchKey.substring(2, bar);
        String rest = matchKey.substring(bar + 1);
        int hash = rest.indexOf('#');
        if (hash < 0 || hash + 1 >= rest.length()) return Optional.empty();
        String ownerBinary = rest.substring(0, hash);
        String nameAndDesc = rest.substring(hash + 1);
        int paren = nameAndDesc.indexOf('(');
        String jvmName = paren < 0 ? nameAndDesc : nameAndDesc.substring(0, paren);
        String bloomName = "<init>".equals(jvmName) ? simpleName(ownerBinary) : jvmName;
        return Optional.of(SymbolKey.of(matchKey, bloomName, origin.isEmpty() ? null : origin));
    }

    /**
     * Call sites in {@code cu} targeting {@code key}, grouped by enclosing
     * method/constructor.
     */
    public static List<CallHierarchyIncomingCall> incomingInUnit(
            CompilationUnit cu,
            SymbolKey key,
            Function<MethodSymbol, Optional<Location>> definitionOf) {
        if (cu == null || key == null || key.fileLocal()) return List.of();
        Objects.requireNonNull(definitionOf, "definitionOf");
        SourceFile.Cursor cursor = cu.source().cursor();
        Map<String, IncomingAccumulator> byCaller = new LinkedHashMap<>();

        cu.accept(new AstVisitor() {
            @Override
            public void visitCallExpr(CallExpr n) {
                MethodSymbol callee = methodSymbol(n.name());
                if (matches(key, callee)) {
                    recordIncoming(n, rangeOf(n.name(), n.range()), callee);
                }
                super.visitCallExpr(n);
            }

            @Override
            public void visitNewExpr(NewExpr n) {
                MethodSymbol ctor = n.constructor();
                if (matches(key, ctor)) {
                    SourceRange range = n.typeNode() != null && n.typeNode().range().isPresent()
                            ? n.typeNode().range()
                            : n.range();
                    recordIncoming(n, range, ctor);
                }
                super.visitNewExpr(n);
            }

            @Override
            public void visitConstructorCallStmt(ConstructorCallStmt n) {
                MethodSymbol ctor = n.symbol();
                if (matches(key, ctor)) {
                    recordIncoming(n, n.range(), ctor);
                }
                super.visitConstructorCallStmt(n);
            }

            @Override
            public void visitMemberRefExpr(MemberRefExpr n) {
                MethodSymbol callee = methodSymbol(n.name());
                if (matches(key, callee)) {
                    recordIncoming(n, rangeOf(n.name(), n.range()), callee);
                }
                super.visitMemberRefExpr(n);
            }

            private void recordIncoming(Node site, SourceRange siteRange, MethodSymbol ignored) {
                CallableSite caller = enclosingCallable(site);
                if (caller == null || caller.symbol() == null) return;
                Optional<Location> callerLoc = definitionOf.apply(caller.symbol());
                if (callerLoc.isEmpty()) return;
                Optional<CallHierarchyItem> from = itemFor(caller.symbol(), callerLoc.get());
                if (from.isEmpty()) return;
                if (siteRange == null || !siteRange.isPresent()) return;
                Range fromRange = AstPositions.rangeOf(cu.source(), siteRange, cursor);
                String mergeKey = mergeKey(from.get());
                byCaller.computeIfAbsent(mergeKey, k -> new IncomingAccumulator(from.get()))
                        .ranges.add(fromRange);
            }
        });

        List<CallHierarchyIncomingCall> out = new ArrayList<>(byCaller.size());
        for (IncomingAccumulator acc : byCaller.values()) {
            out.add(new CallHierarchyIncomingCall(acc.from, List.copyOf(acc.ranges)));
        }
        return out;
    }

    /**
     * Callees invoked from the body of the method identified by {@code key}
     * in {@code cu}. Nested types and methods are not walked as part of that body.
     */
    public static List<CallHierarchyOutgoingCall> outgoingInUnit(
            CompilationUnit cu,
            SymbolKey key,
            Function<MethodSymbol, Optional<Location>> definitionOf) {
        if (cu == null || key == null || key.fileLocal()) return List.of();
        Objects.requireNonNull(definitionOf, "definitionOf");
        CallableSite target = findCallable(cu, key);
        if (target == null || target.body() == null) return List.of();

        SourceFile.Cursor cursor = cu.source().cursor();
        Map<String, OutgoingAccumulator> byCallee = new LinkedHashMap<>();

        target.body().accept(new AstVisitor() {
            @Override
            public void visitMethodDecl(MethodDecl n) {
                // Nested methods are not part of the outer method's outgoing set.
            }

            @Override
            public void visitConstructorDecl(ConstructorDecl n) {}

            @Override
            public void visitCompactConstructorDecl(CompactConstructorDecl n) {}

            @Override
            public void visitTypeDecl(TypeDecl n) {
                // Nested / local / anonymous types are separate callables.
            }

            @Override
            public void visitCallExpr(CallExpr n) {
                MethodSymbol callee = methodSymbol(n.name());
                if (callee != null) {
                    recordOutgoing(rangeOf(n.name(), n.range()), callee);
                }
                visit(n.receiver());
                visit(n.name());
                visitAll(n.typeArguments());
                visitAll(n.arguments());
            }

            @Override
            public void visitNewExpr(NewExpr n) {
                MethodSymbol ctor = n.constructor();
                if (ctor != null) {
                    SourceRange range = n.typeNode() != null && n.typeNode().range().isPresent()
                            ? n.typeNode().range()
                            : n.range();
                    recordOutgoing(range, ctor);
                }
                visit(n.enclosing());
                visit(n.typeNode());
                visitAll(n.typeArguments());
                visitAll(n.arguments());
                // Skip anonymousBody (nested type).
            }

            @Override
            public void visitConstructorCallStmt(ConstructorCallStmt n) {
                MethodSymbol ctor = n.symbol();
                if (ctor != null) {
                    recordOutgoing(n.range(), ctor);
                }
                visit(n.qualifier());
                visitAll(n.typeArguments());
                visitAll(n.arguments());
            }

            @Override
            public void visitMemberRefExpr(MemberRefExpr n) {
                MethodSymbol callee = methodSymbol(n.name());
                if (callee != null) {
                    recordOutgoing(rangeOf(n.name(), n.range()), callee);
                }
                visit(n.qualifierExpr());
                visit(n.qualifierType());
                visit(n.name());
                visitAll(n.typeArguments());
            }

            private void recordOutgoing(SourceRange siteRange, MethodSymbol callee) {
                if (callee == null || callee.key() == null || callee.key().fileLocal()) return;
                Optional<Location> loc = definitionOf.apply(callee);
                if (loc.isEmpty()) return;
                Optional<CallHierarchyItem> to = itemFor(callee, loc.get());
                if (to.isEmpty()) return;
                if (siteRange == null || !siteRange.isPresent()) return;
                Range fromRange = AstPositions.rangeOf(cu.source(), siteRange, cursor);
                String mergeKey = mergeKey(to.get());
                byCallee.computeIfAbsent(mergeKey, k -> new OutgoingAccumulator(to.get()))
                        .ranges.add(fromRange);
            }
        });

        List<CallHierarchyOutgoingCall> out = new ArrayList<>(byCallee.size());
        for (OutgoingAccumulator acc : byCallee.values()) {
            out.add(new CallHierarchyOutgoingCall(acc.to, List.copyOf(acc.ranges)));
        }
        return out;
    }

    private static CallableSite findCallable(CompilationUnit cu, SymbolKey key) {
        CallableSite[] found = new CallableSite[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitMethodDecl(MethodDecl n) {
                if (found[0] == null && matches(key, n.symbol())) {
                    found[0] = new CallableSite(n.symbol(), n.body(), n.name());
                    return;
                }
                super.visitMethodDecl(n);
            }

            @Override
            public void visitConstructorDecl(ConstructorDecl n) {
                if (found[0] == null && matches(key, n.symbol())) {
                    found[0] = new CallableSite(n.symbol(), n.body(), n.name());
                    return;
                }
                super.visitConstructorDecl(n);
            }

            @Override
            public void visitCompactConstructorDecl(CompactConstructorDecl n) {
                if (found[0] == null && matches(key, n.symbol())) {
                    found[0] = new CallableSite(n.symbol(), n.body(), n.name());
                    return;
                }
                super.visitCompactConstructorDecl(n);
            }
        });
        return found[0];
    }

    private static CallableSite enclosingCallable(Node node) {
        Node n = node == null ? null : node.parent();
        while (n != null) {
            if (n instanceof MethodDecl method) {
                return new CallableSite(method.symbol(), method.body(), method.name());
            }
            if (n instanceof ConstructorDecl ctor) {
                return new CallableSite(ctor.symbol(), ctor.body(), ctor.name());
            }
            if (n instanceof CompactConstructorDecl compact) {
                return new CallableSite(compact.symbol(), compact.body(), compact.name());
            }
            n = n.parent();
        }
        return null;
    }

    private static boolean matches(SymbolKey key, MethodSymbol method) {
        return method != null && method.key() != null && key.matches(method.key());
    }

    private static MethodSymbol methodSymbol(Identifier name) {
        if (name == null) return null;
        Symbol symbol = name.symbol();
        return symbol instanceof MethodSymbol method ? method : null;
    }

    private static SourceRange rangeOf(Identifier name, SourceRange fallback) {
        if (name != null && name.range().isPresent()) return name.range();
        return fallback;
    }

    private static String displayName(MethodSymbol method) {
        if (method.constructor() || "<init>".equals(method.name())) {
            if (method.owner() != null) {
                return simpleName(method.owner().jvmBinaryName().replace('/', '.'));
            }
            return method.name();
        }
        return method.name();
    }

    private static String detailOf(MethodSymbol method) {
        if (method.owner() == null) return "";
        return method.owner().jvmBinaryName().replace('/', '.').replace('$', '.');
    }

    private static String simpleName(String binaryOrJvm) {
        int cut = Math.max(binaryOrJvm.lastIndexOf('.'), binaryOrJvm.lastIndexOf('$'));
        cut = Math.max(cut, binaryOrJvm.lastIndexOf('/'));
        return cut < 0 ? binaryOrJvm : binaryOrJvm.substring(cut + 1);
    }

    private static String mergeKey(CallHierarchyItem item) {
        return matchKeyString(item.getData()) + "|" + item.getUri();
    }

    private static String matchKeyString(Object data) {
        if (data instanceof String string) {
            return string;
        }
        if (data instanceof JsonElement element && element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return null;
    }

    private record CallableSite(MethodSymbol symbol, Block body, Identifier name) {}

    private static final class IncomingAccumulator {
        final CallHierarchyItem from;
        final List<Range> ranges = new ArrayList<>();

        IncomingAccumulator(CallHierarchyItem from) {
            this.from = from;
        }
    }

    private static final class OutgoingAccumulator {
        final CallHierarchyItem to;
        final List<Range> ranges = new ArrayList<>();

        OutgoingAccumulator(CallHierarchyItem to) {
            this.to = to;
        }
    }
}
