/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.objectweb.asm.Opcodes;

import ch.castleridge.javals.ast.AstVisitor;
import ch.castleridge.javals.ast.Block;
import ch.castleridge.javals.ast.CompilationUnit;
import ch.castleridge.javals.ast.ConstructorDecl;
import ch.castleridge.javals.ast.Declaration;
import ch.castleridge.javals.ast.EnumConstantDecl;
import ch.castleridge.javals.ast.Expression;
import ch.castleridge.javals.ast.FieldDecl;
import ch.castleridge.javals.ast.FieldSymbol;
import ch.castleridge.javals.ast.Identifier;
import ch.castleridge.javals.ast.ImportDecl;
import ch.castleridge.javals.ast.JType;
import ch.castleridge.javals.ast.LambdaExpr;
import ch.castleridge.javals.ast.LocalDeclStmt;
import ch.castleridge.javals.ast.LocalSymbol;
import ch.castleridge.javals.ast.MethodDecl;
import ch.castleridge.javals.ast.MethodSymbol;
import ch.castleridge.javals.ast.NameExpr;
import ch.castleridge.javals.ast.Node;
import ch.castleridge.javals.ast.PackageSymbol;
import ch.castleridge.javals.ast.ParamDecl;
import ch.castleridge.javals.ast.RecordComponentDecl;
import ch.castleridge.javals.ast.Select;
import ch.castleridge.javals.ast.SourceFile;
import ch.castleridge.javals.ast.Statement;
import ch.castleridge.javals.ast.Symbol;
import ch.castleridge.javals.ast.TypeDecl;
import ch.castleridge.javals.ast.TypeName;
import ch.castleridge.javals.ast.TypeSymbol;
import ch.castleridge.javals.ast.VarFragment;
import ch.castleridge.javals.classpath.ClasspathOrder;
import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.indexing.model.ClassFileTypeEntry;
import ch.castleridge.javals.indexing.model.FieldEntry;
import ch.castleridge.javals.indexing.model.MethodEntry;
import ch.castleridge.javals.indexing.model.SourceTypeEntry;
import ch.castleridge.javals.indexing.model.Type;
import ch.castleridge.javals.indexing.model.TypeEntry;

final class AstCompleter {

    private static final int UNIMPORTED_TYPE_LIMIT = 50;

    private AstCompleter() {}

    static List<CompletionItem> complete(CompilationUnit cu,
                                         CharSequence source,
                                         Position position,
                                         Index index,
                                         ClasspathOrder classpath) {
        if (cu == null || cu.source() == null) return List.of();
        String text = source == null ? cu.source().text() : source.toString();
        int offset = AstPositions.offsetAt(cu.source(), position);
        if (offset < 0) offset = 0;
        offset = Math.min(text.length(), offset);

        int identifierStart = offset;
        while (identifierStart > 0 && Character.isJavaIdentifierPart(text.charAt(identifierStart - 1))) {
            identifierStart--;
        }
        String prefix = text.substring(identifierStart, offset);
        int dot = identifierStart > 0 && text.charAt(identifierStart - 1) == '.' ? identifierStart - 1 : -1;
        if (dot >= 0) {
            return qualified(cu, text, dot, prefix, index, classpath);
        }
        return unqualified(cu, identifierStart, prefix, index, classpath);
    }

    private static List<CompletionItem> qualified(CompilationUnit cu,
                                                  String text,
                                                  int dot,
                                                  String prefix,
                                                  Index index,
                                                  ClasspathOrder classpath) {
        if (index == null) return List.of();
        Node node = cu.nodeAt(Math.max(0, dot - 1));
        Identifier ident = AstSymbols.identifierAt(cu, Math.max(0, dot - 1));
        Qualifier qualifier = qualifierOf(ident != null ? ident : node, text, dot);
        if (qualifier == null) {
            Expression receiver = receiverAt(cu, text, dot);
            if (receiver != null) qualifier = qualifierOf(receiver, text, dot);
        }
        if (qualifier == null) return List.of();
        if (qualifier.packageJvm != null) {
            return packageTypes(index, classpath, qualifier.packageJvm, prefix);
        }
        String jvm = qualifier.jvmOwner;
        if (jvm == null) return List.of();
        Map<String, CompletionItem> items = new LinkedHashMap<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        collectIndexMembers(index, classpath, jvm, prefix, qualifier.typeName, items, seenTypes);
        if (qualifier.array && "length".startsWith(prefix)) {
            items.putIfAbsent("F:length", fieldItem("length", "int"));
        }
        return List.copyOf(items.values());
    }

    private static List<CompletionItem> unqualified(CompilationUnit cu,
                                                    int offset,
                                                    String prefix,
                                                    Index index,
                                                    ClasspathOrder classpath) {
        Map<String, CompletionItem> items = new LinkedHashMap<>();
        Node node = cu.nodeAt(Math.max(0, offset - (offset > 0 ? 1 : 0)));
        if (node == null) node = cu;

        addLocalsAndParams(node, offset, prefix, items);
        TypeDecl enclosing = enclosingType(node, offset);
        if (enclosing == null) {
            for (TypeDecl type : cu.types()) {
                addDeclaredMembers(type, prefix, items);
                if (type.symbol() != null && index != null) {
                    collectIndexMembers(index, classpath, slash(type.symbol().jvmBinaryName()),
                            prefix, false, items, new LinkedHashSet<>());
                }
            }
        }
        while (enclosing != null) {
            addDeclaredMembers(enclosing, prefix, items);
            if (enclosing.symbol() != null && index != null) {
                collectIndexMembers(index, classpath, slash(enclosing.symbol().jvmBinaryName()),
                        prefix, false, items, new LinkedHashSet<>());
            }
            enclosing = enclosing.enclosing(TypeDecl.class);
        }
        addImported(cu, prefix, items);
        if (index != null && classpath != null && !prefix.isEmpty()) {
            addUnimportedTypes(cu, prefix, index, classpath, items);
        }
        return List.copyOf(items.values());
    }

    private static TypeDecl enclosingType(Node node, int offset) {
        if (node instanceof TypeDecl type) return type;
        TypeDecl enclosing = node == null ? null : node.enclosing(TypeDecl.class);
        if (enclosing != null) return enclosing;
        CompilationUnit cu = node == null ? null : node.cu();
        if (cu == null) return null;
        TypeDecl[] best = new TypeDecl[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitTypeDecl(TypeDecl n) {
                if (AstSymbols.covers(n.range(), offset)
                        && (best[0] == null || AstSymbols.span(n.range()) <= AstSymbols.span(best[0].range()))) {
                    best[0] = n;
                }
                visitChildren(n);
            }
        });
        return best[0];
    }

    private static void addLocalsAndParams(Node node, int offset, String prefix, Map<String, CompletionItem> items) {
        MethodDecl method = node instanceof MethodDecl m ? m
                : node == null ? null : node.enclosing(MethodDecl.class);
        if (method == null) method = methodCovering(node, offset);
        if (method != null) {
            for (ParamDecl param : method.parameters()) addLocal(param.symbol(), param.name(), prefix, items);
        }
        ConstructorDecl ctor = node instanceof ConstructorDecl c ? c
                : node == null ? null : node.enclosing(ConstructorDecl.class);
        if (ctor == null) ctor = constructorCovering(node, offset);
        if (ctor != null) {
            for (ParamDecl param : ctor.parameters()) addLocal(param.symbol(), param.name(), prefix, items);
        }
        LambdaExpr lambda = node instanceof LambdaExpr l ? l : node.enclosing(LambdaExpr.class);
        if (lambda != null) {
            for (ParamDecl param : lambda.parameters()) addLocal(param.symbol(), param.name(), prefix, items);
        }
        Block block = node instanceof Block b ? b : node.enclosing(Block.class);
        if (block == null && method != null) block = method.body();
        if (block == null && ctor != null) block = ctor.body();
        while (block != null) {
            for (Statement stmt : block.statements()) {
                if (stmt.range().isPresent() && stmt.range().start() >= offset) continue;
                if (stmt instanceof LocalDeclStmt local) {
                    for (VarFragment fragment : local.fragments()) {
                        addLocal(fragment.symbol(), fragment.name(), prefix, items);
                    }
                }
            }
            Node parent = block.parent();
            block = parent == null ? null : parent.enclosing(Block.class);
        }
    }

    private static void addLocal(Symbol symbol, Identifier name, String prefix, Map<String, CompletionItem> items) {
        String label = name == null ? (symbol == null ? "" : symbol.name()) : name.name();
        if (label.isEmpty() || !label.startsWith(prefix)) return;
        items.putIfAbsent("n:" + label, variableItem(label, typeName(symbol == null ? null : symbol.type())));
    }

    private static void addDeclaredMembers(TypeDecl type, String prefix, Map<String, CompletionItem> items) {
        for (Declaration member : type.members()) {
            if (member instanceof FieldDecl field) {
                for (VarFragment fragment : field.fragments()) {
                    String name = fragment.name() == null ? "" : fragment.name().name();
                    if (name.startsWith(prefix)) {
                        items.putIfAbsent("n:" + name, fieldItem(name, typeName(fragment.symbol())));
                    }
                }
            } else if (member instanceof EnumConstantDecl constant && constant.name() != null) {
                String name = constant.name().name();
                if (name.startsWith(prefix)) {
                    items.putIfAbsent("n:" + name, enumItem(name, type.name() == null ? "" : type.name().name()));
                }
            } else if (member instanceof MethodDecl method && method.name() != null) {
                String name = method.name().name();
                if (name.startsWith(prefix)) {
                    items.putIfAbsent(methodKey(method.symbol(), name), methodItem(method.symbol(), name));
                }
            } else if (member instanceof TypeDecl nested && nested.name() != null) {
                String name = nested.name().name();
                if (name.startsWith(prefix)) {
                    items.putIfAbsent("n:" + name, typeItem(name, nested.symbol()));
                }
            }
        }
        for (RecordComponentDecl component : type.recordComponents()) {
            if (component.name() == null) continue;
            String name = component.name().name();
            if (name.startsWith(prefix)) {
                items.putIfAbsent("n:" + name, fieldItem(name, typeName(component.symbol())));
            }
        }
    }

    private static void addImported(CompilationUnit cu, String prefix, Map<String, CompletionItem> items) {
        for (ImportDecl imp : cu.imports()) {
            if (imp.onDemand() || imp.names().isEmpty()) continue;
            Identifier last = imp.names().get(imp.names().size() - 1);
            if (!last.name().startsWith(prefix)) continue;
            items.putIfAbsent("n:" + last.name(), last.symbol() instanceof TypeSymbol type
                    ? typeItem(last.name(), type)
                    : variableItem(last.name(), typeName(last.symbol())));
        }
    }

    private static void addUnimportedTypes(CompilationUnit cu,
                                           String prefix,
                                           Index index,
                                           ClasspathOrder classpath,
                                           Map<String, CompletionItem> items) {
        String currentPackage = cu.packageDecl() == null ? "" : cu.packageDecl().qualifiedName();
        Set<String> imported = importedNames(cu);
        int added = 0;
        for (TypeEntry entry : index.searchTypesBySimpleNamePrefix(prefix, UNIMPORTED_TYPE_LIMIT)) {
            if (classpath.pick(index.getAll(entry.jvmOwnerName()), TypeEntry::sourceUri) != entry) continue;
            String simple = simpleName(entry.jvmOwnerName());
            if (items.containsKey("n:" + simple)) continue;
            String fqcn = entry.jvmOwnerName().replace('/', '.').replace('$', '.');
            CompletionItem item = new CompletionItem(simple);
            item.setKind(typeKind(entry));
            item.setDetail(fqcn);
            item.setInsertText(simple);
            item.setSortText("1_" + simple);
            boolean visible = packageOf(entry.jvmOwnerName()).equals(currentPackage)
                    || "java.lang".equals(packageOf(entry.jvmOwnerName()))
                    || imported.contains(fqcn)
                    || imported.contains(packageOf(entry.jvmOwnerName()) + ".*");
            if (!visible) {
                TextEdit edit = importEdit(cu, fqcn);
                if (edit != null) item.setAdditionalTextEdits(List.of(edit));
            }
            items.putIfAbsent("T:" + entry.jvmOwnerName(), item);
            if (++added >= UNIMPORTED_TYPE_LIMIT) break;
        }
    }

    private static Set<String> importedNames(CompilationUnit cu) {
        Set<String> out = new LinkedHashSet<>();
        for (ImportDecl imp : cu.imports()) {
            if (imp.staticImport()) continue;
            StringBuilder fqcn = new StringBuilder();
            for (int i = 0; i < imp.names().size(); i++) {
                if (i > 0) fqcn.append('.');
                fqcn.append(imp.names().get(i).name());
            }
            if (imp.onDemand()) fqcn.append(".*");
            out.add(fqcn.toString());
        }
        return out;
    }

    private static TextEdit importEdit(CompilationUnit cu, String fqcn) {
        SourceFile source = cu.source();
        int insert = 0;
        if (!cu.imports().isEmpty()) {
            insert = cu.imports().get(cu.imports().size() - 1).range().end();
        } else if (cu.packageDecl() != null) {
            insert = cu.packageDecl().range().end();
        }
        while (insert < source.length() && source.text().charAt(insert) != '\n') insert++;
        if (insert < source.length() && source.text().charAt(insert) == '\n') insert++;
        Position pos = AstPositions.positionAt(source, insert);
        return new TextEdit(new Range(pos, pos), "import " + fqcn + ";\n");
    }

    private static void collectIndexMembers(Index index,
                                            ClasspathOrder classpath,
                                            String jvmOwner,
                                            String prefix,
                                            boolean staticOnly,
                                            Map<String, CompletionItem> items,
                                            Set<String> seenTypes) {
        if (jvmOwner == null || jvmOwner.isEmpty() || !seenTypes.add(jvmOwner) || index == null) return;
        TypeEntry entry = classpath == null
                ? null
                : classpath.pick(index.getAll(jvmOwner), TypeEntry::sourceUri);
        if (entry == null) return;
        for (FieldEntry field : entry.fields()) {
            if (!field.name().startsWith(prefix)) continue;
            boolean isStatic = (field.modifiers() & Opcodes.ACC_STATIC) != 0;
            if (staticOnly && !isStatic) continue;
            items.putIfAbsent("F:" + field.name(), fieldItem(field.name(), typeString(field.type())));
        }
        for (MethodEntry method : entry.methods()) {
            if (method.name().startsWith("<")) continue;
            if (!method.name().startsWith(prefix)) continue;
            boolean isStatic = (method.modifiers() & Opcodes.ACC_STATIC) != 0;
            if (staticOnly && !isStatic) continue;
            items.putIfAbsent(methodKey(method), methodItem(method, simpleName(entry.jvmOwnerName())));
        }
        for (String nested : entry.innerTypeJvmNames()) {
            String simple = simpleName(nested);
            if (simple.startsWith(prefix)) {
                items.putIfAbsent("n:" + simple, typeItem(simple, nested.replace('/', '.').replace('$', '.')));
            }
        }
        for (String sup : TypeHierarchySupport.directSuperJvmNames(entry, index, classpath)) {
            collectIndexMembers(index, classpath, sup, prefix, staticOnly, items, seenTypes);
        }
    }

    private static List<CompletionItem> packageTypes(Index index,
                                                     ClasspathOrder classpath,
                                                     String packageJvm,
                                                     String prefix) {
        List<CompletionItem> items = new ArrayList<>();
        String wanted = packageJvm.endsWith("/") ? packageJvm : packageJvm + "/";
        Iterable<TypeEntry> candidates = prefix.isEmpty()
                ? index.all()
                : index.searchTypesBySimpleNamePrefix(prefix, UNIMPORTED_TYPE_LIMIT * 4);
        for (TypeEntry entry : candidates) {
            if (classpath != null
                    && classpath.pick(index.getAll(entry.jvmOwnerName()), TypeEntry::sourceUri) != entry) {
                continue;
            }
            String jvm = entry.jvmOwnerName();
            if (!jvm.startsWith(wanted)) continue;
            String rest = jvm.substring(wanted.length());
            if (rest.indexOf('/') >= 0 || rest.indexOf('$') >= 0) continue;
            if (!rest.startsWith(prefix)) continue;
            items.add(typeItem(rest, jvm.replace('/', '.').replace('$', '.')));
            if (items.size() >= UNIMPORTED_TYPE_LIMIT) break;
        }
        return items;
    }

    private static Expression receiverAt(CompilationUnit cu, String text, int dot) {
        int probe = Math.min(text.length() - 1, Math.max(0, dot + 1));
        Node afterDot = cu.nodeAt(probe);
        Select select = afterDot instanceof Select s ? s
                : afterDot == null ? null : afterDot.enclosing(Select.class);
        return select == null ? null : select.receiver();
    }

    private static MethodDecl methodCovering(Node node, int offset) {
        CompilationUnit cu = node == null ? null : node.cu();
        if (cu == null) return null;
        MethodDecl[] best = new MethodDecl[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitMethodDecl(MethodDecl n) {
                if (AstSymbols.covers(n.range(), offset)
                        && (best[0] == null || AstSymbols.span(n.range()) < AstSymbols.span(best[0].range()))) {
                    best[0] = n;
                }
                visitChildren(n);
            }
        });
        return best[0];
    }

    private static ConstructorDecl constructorCovering(Node node, int offset) {
        CompilationUnit cu = node == null ? null : node.cu();
        if (cu == null) return null;
        ConstructorDecl[] best = new ConstructorDecl[1];
        cu.accept(new AstVisitor() {
            @Override
            public void visitConstructorDecl(ConstructorDecl n) {
                if (AstSymbols.covers(n.range(), offset)
                        && (best[0] == null || AstSymbols.span(n.range()) < AstSymbols.span(best[0].range()))) {
                    best[0] = n;
                }
                visitChildren(n);
            }
        });
        return best[0];
    }

    private static Qualifier qualifierOf(Node node, String text, int dot) {
        if (node == null) return null;
        if (node instanceof Select select && select.receiver() != null) {
            node = select.receiver();
        }
        Identifier ident = nearestIdent(node);
        if (ident != null && ident.symbol() instanceof PackageSymbol pkg) {
            return Qualifier.pkg(slash(pkg.qualifiedName()));
        }
        if (ident != null && ident.symbol() instanceof TypeSymbol type) {
            return Qualifier.type(slash(type.jvmBinaryName()));
        }
        if (ident != null && ident.symbol() != null) {
            String jvm = jvmOf(ident.symbol().type());
            if (jvm != null) return Qualifier.instance(jvm);
        }
        Expression expr = node instanceof Expression e ? e : node.enclosing(Expression.class);
        if (expr != null) {
            if (expr instanceof NameExpr name && name.name() != null && name.name().symbol() instanceof TypeSymbol type) {
                return Qualifier.type(slash(type.jvmBinaryName()));
            }
            if (expr instanceof Select select && select.name() != null
                    && select.name().symbol() instanceof TypeSymbol type) {
                return Qualifier.type(slash(type.jvmBinaryName()));
            }
            JType type = expr.type();
            if (type instanceof JType.Array) {
                JType leaf = type;
                while (leaf instanceof JType.Array array) leaf = array.element();
                String jvm = jvmOf(leaf);
                return new Qualifier(jvm, false, true, null);
            }
            String jvm = jvmOf(type);
            if (jvm != null) return Qualifier.instance(jvm);
        }
        String dotted = dottedNameBefore(text, dot);
        if (dotted != null && dotted.indexOf('.') >= 0) return Qualifier.pkg(dotted.replace('.', '/'));
        return null;
    }

    private static Identifier nearestIdent(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof Identifier ident) return ident;
            if (current instanceof NameExpr name) return name.name();
            if (current instanceof Select select) return select.name();
            if (current instanceof TypeName type) return type.simpleName();
            current = current.parent();
        }
        return null;
    }

    private static String dottedNameBefore(String text, int dot) {
        int end = dot;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') start--;
            else break;
        }
        if (start >= end) return null;
        String name = text.substring(start, end);
        if (name.isEmpty() || !name.chars().allMatch(c -> c == '.' || Character.isJavaIdentifierPart((char) c))) {
            return null;
        }
        return name;
    }

    private static String jvmOf(JType type) {
        return switch (type) {
            case JType.Declared declared -> slash(declared.jvmBinaryName());
            case JType.Array array -> jvmOf(array.element());
            case null, default -> null;
        };
    }

    private static String slash(String binary) {
        return binary == null ? null : binary.replace('.', '/');
    }

    private static String simpleName(String jvm) {
        int cut = Math.max(jvm.lastIndexOf('/'), jvm.lastIndexOf('$'));
        return cut < 0 ? jvm : jvm.substring(cut + 1);
    }

    private static String packageOf(String jvm) {
        int slash = jvm.lastIndexOf('/');
        return slash < 0 ? "" : jvm.substring(0, slash).replace('/', '.');
    }

    private static String typeName(Symbol symbol) {
        return symbol == null ? "" : typeName(symbol.type());
    }

    private static String typeName(JType type) {
        if (type == null) return "";
        return switch (type) {
            case JType.Primitive primitive -> primitive.name().toLowerCase();
            default -> type.toString();
        };
    }

    private static String typeString(Type type) {
        if (type == null) return "";
        Type plain = type instanceof Type.Annotated annotated ? annotated.unwrap() : type;
        return switch (plain) {
            case Type.Primitive primitive -> primitive == Type.Primitive.VOID
                    ? "void" : primitive.name().toLowerCase();
            case Type.Array array -> typeString(array.element()) + "[]";
            case Type.TypeVariable variable -> variable.name();
            case Type.Wildcard wild -> switch (wild.kind()) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + typeString(wild.bound());
                case SUPER -> "? super " + typeString(wild.bound());
            };
            case Type.Parameterized parameterized -> {
                StringBuilder sb = new StringBuilder(typeString(parameterized.raw())).append('<');
                Type[] args = parameterized.typeArgs();
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(typeString(args[i]));
                }
                yield sb.append('>').toString();
            }
            case ch.castleridge.javals.indexing.model.TypeRef.Resolved resolved ->
                    resolved.jvmBinaryName().replace('/', '.').replace('$', '.');
            case ch.castleridge.javals.indexing.model.TypeRef.Unresolved unresolved -> unresolved.simpleName();
            default -> plain.toString();
        };
    }

    private static CompletionItem fieldItem(String name, String detail) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Field);
        item.setDetail(detail);
        item.setInsertText(name);
        item.setSortText("0_" + name);
        return item;
    }

    private static CompletionItem variableItem(String name, String detail) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Variable);
        item.setDetail(detail);
        item.setInsertText(name);
        item.setSortText("0_" + name);
        return item;
    }

    private static CompletionItem enumItem(String name, String detail) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.EnumMember);
        item.setDetail(detail);
        item.setInsertText(name);
        item.setSortText("0_" + name);
        return item;
    }

    private static CompletionItem methodItem(MethodSymbol symbol, String name) {
        String detail = symbol == null ? name + "()" : signature(symbol);
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Method);
        item.setDetail(detail);
        item.setInsertText(name + "($0)");
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setSortText("0_" + name);
        return item;
    }

    private static CompletionItem methodItem(MethodEntry method, String owner) {
        CompletionItem item = new CompletionItem(method.name());
        item.setKind(CompletionItemKind.Method);
        item.setDetail(signature(method));
        item.setInsertText(method.name() + "($0)");
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setSortText("0_" + method.name());
        return item;
    }

    private static CompletionItem typeItem(String name, TypeSymbol symbol) {
        return typeItem(name, symbol == null ? name : symbol.jvmBinaryName().replace('/', '.').replace('$', '.'));
    }

    private static CompletionItem typeItem(String name, String detail) {
        CompletionItem item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Class);
        item.setDetail(detail);
        item.setInsertText(name);
        item.setSortText("0_" + name);
        return item;
    }

    private static CompletionItemKind typeKind(TypeEntry entry) {
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

    private static String signature(MethodSymbol symbol) {
        StringBuilder sb = new StringBuilder();
        if (!symbol.constructor()) sb.append(symbol.returnType()).append(' ');
        sb.append(symbol.name()).append('(');
        List<JType> params = symbol.parameterTypes();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i));
        }
        return sb.append(')').toString();
    }

    private static String signature(MethodEntry method) {
        StringBuilder sb = new StringBuilder();
        if (!"<init>".equals(method.name())) sb.append(typeString(method.returnType())).append(' ');
        sb.append(method.name()).append('(');
        Type[] params = method.paramTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(typeString(params[i]));
        }
        return sb.append(')').toString();
    }

    private static String methodKey(MethodSymbol symbol, String name) {
        if (symbol == null) return "M:" + name;
        return "M:" + name + signature(symbol);
    }

    private static String methodKey(MethodEntry method) {
        return "M:" + method.name() + signature(method);
    }

    private record Qualifier(String jvmOwner, boolean typeName, boolean array, String packageJvm) {
        static Qualifier type(String jvm) {
            return new Qualifier(jvm, true, false, null);
        }

        static Qualifier instance(String jvm) {
            return new Qualifier(jvm, false, false, null);
        }

        static Qualifier pkg(String jvm) {
            return new Qualifier(null, false, false, jvm);
        }
    }
}
