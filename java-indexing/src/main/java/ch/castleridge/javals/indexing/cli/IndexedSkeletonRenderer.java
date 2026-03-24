package ch.castleridge.javals.indexing.cli;

import ch.castleridge.javals.indexing.declaration.DeclarationFields;
import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders Java-like declaration skeletons from declaration index rows (no method bodies). */
public final class IndexedSkeletonRenderer {

    private IndexedSkeletonRenderer() {}

    /**
     * Stable-sorted skeletons by fully qualified name, separated by a blank line (no trailing
     * blank line after the last skeleton).
     */
    public static String renderAll(List<IndexEntry> entries) {
        Map<String, List<IndexEntry>> byResource = new LinkedHashMap<>();
        for (IndexEntry e : entries) {
            String uri = e.field(DeclarationFields.RESOURCE_URI);
            if (uri == null) {
                continue;
            }
            byResource.computeIfAbsent(uri, k -> new ArrayList<>()).add(e);
        }
        record NamedBlock(String fqn, String text) {}
        List<NamedBlock> blocks = new ArrayList<>();
        for (Map.Entry<String, List<IndexEntry>> e : byResource.entrySet()) {
            String block = renderResource(e.getKey(), e.getValue());
            if (block == null || block.isEmpty()) {
                continue;
            }
            String fqn = fqnForResource(e.getValue());
            blocks.add(new NamedBlock(fqn, block));
        }
        blocks.sort(Comparator.comparing(NamedBlock::fqn, String::compareTo));
        return blocks.stream().map(NamedBlock::text).collect(Collectors.joining("\n\n"));
    }

    private static String fqnForResource(List<IndexEntry> rows) {
        for (IndexEntry r : rows) {
            if (DeclarationIndex.KIND_TYPE.equals(r.field(DeclarationFields.KIND))) {
                String internal = r.field(DeclarationFields.JVM_NAME);
                if (internal != null && !internal.isEmpty()) {
                    return internal.replace('/', '.');
                }
            }
        }
        return "";
    }

    private static String renderResource(String resourceUri, List<IndexEntry> rows) {
        IndexEntry typeRow = null;
        for (IndexEntry r : rows) {
            if (DeclarationIndex.KIND_TYPE.equals(r.field(DeclarationFields.KIND))) {
                typeRow = r;
                break;
            }
        }
        if (typeRow == null) {
            return "";
        }
        String internal = typeRow.field(DeclarationFields.JVM_NAME);
        if (internal == null || internal.isEmpty()) {
            return "";
        }
        int typeAccess = parseAccess(typeRow);
        List<IndexEntry> fields = new ArrayList<>();
        List<IndexEntry> methods = new ArrayList<>();
        for (IndexEntry r : rows) {
            String kind = r.field(DeclarationFields.KIND);
            if (internal.equals(r.field(DeclarationFields.JVM_NAME))) {
                if (DeclarationIndex.KIND_FIELD.equals(kind)) {
                    fields.add(r);
                } else if (DeclarationIndex.KIND_METHOD.equals(kind)) {
                    methods.add(r);
                }
            }
        }
        fields.sort(Comparator.comparing(r -> r.field(DeclarationFields.MEMBER_NAME), Comparator.nullsFirst(String::compareTo)));
        methods.sort(
                Comparator.comparing((IndexEntry r) -> r.field(DeclarationFields.MEMBER_NAME), Comparator.nullsFirst(String::compareTo))
                        .thenComparing(r -> r.field(DeclarationFields.DESCRIPTOR), Comparator.nullsFirst(String::compareTo)));

        StringBuilder out = new StringBuilder();
        String pkg = packageName(internal);
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        appendAnnotations(out, typeRow.field(DeclarationFields.ANNOTATIONS), "");
        String typeKeyword = typeKeyword(typeAccess);
        appendTypeModifiers(out, typeAccess, typeKeyword);
        out.append(typeKeyword).append(' ');
        out.append(simpleName(internal));
        String typeSig = typeRow.field(DeclarationFields.TYPE_PARAMS);
        if (typeSig != null && !typeSig.isEmpty()) {
            out.append(" /* generic signature: ").append(typeSig).append(" */");
        }
        String extendsJvm = nullToEmpty(typeRow.field(DeclarationFields.EXTENDS));
        String implementsJvm = nullToEmpty(typeRow.field(DeclarationFields.IMPLEMENTS));
        if ((typeAccess & Opcodes.ACC_INTERFACE) != 0) {
            if (!implementsJvm.isEmpty()) {
                out.append(" extends ");
                out.append(formatTypeList(implementsJvm));
            }
        } else if ((typeAccess & Opcodes.ACC_ENUM) == 0) {
            if (!extendsJvm.isEmpty() && !"java/lang/Object".equals(extendsJvm)) {
                out.append(" extends ").append(toJavaType(extendsJvm));
            }
            if (!implementsJvm.isEmpty()) {
                out.append(" implements ").append(formatTypeList(implementsJvm));
            }
        } else if ((typeAccess & Opcodes.ACC_ENUM) != 0 && !implementsJvm.isEmpty()) {
            out.append(" implements ").append(formatTypeList(implementsJvm));
        }
        out.append(" {\n");
        for (IndexEntry f : fields) {
            renderField(out, f, "  ");
        }
        for (IndexEntry m : methods) {
            int ma = parseAccess(m);
            if ((ma & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            renderMethod(out, m, internal, "  ");
        }
        out.append("}\n");
        return out.toString();
    }

    private static void renderField(StringBuilder out, IndexEntry f, String indent) {
        appendAnnotations(out, f.field(DeclarationFields.ANNOTATIONS), indent);
        int a = parseAccess(f);
        out.append(indent).append(fieldModifiers(a));
        String t = toJavaType(nullToEmpty(f.field(DeclarationFields.DECLARED_TYPE)));
        out.append(t).append(' ').append(f.field(DeclarationFields.MEMBER_NAME)).append(";\n");
    }

    private static void renderMethod(StringBuilder out, IndexEntry m, String ownerInternal, String indent) {
        String name = m.field(DeclarationFields.MEMBER_NAME);
        int a = parseAccess(m);
        if ("<clinit>".equals(name)) {
            appendAnnotations(out, m.field(DeclarationFields.ANNOTATIONS), indent);
            out.append(indent).append("static {\n");
            out.append(indent).append("}\n");
            return;
        }
        appendAnnotations(out, m.field(DeclarationFields.ANNOTATIONS), indent);
        out.append(indent).append(methodModifiers(a));
        if ("<init>".equals(name)) {
            out.append(simpleName(ownerInternal));
            out.append(renderParameterList(m));
            out.append(renderThrows(m));
            String sig = m.field(DeclarationFields.TYPE_PARAMS);
            if (sig != null && !sig.isEmpty()) {
                out.append(" /* generic signature: ").append(sig).append(" */");
            }
            out.append(" {}\n");
            return;
        }
        String ret = toJavaType(nullToEmpty(m.field(DeclarationFields.RETURN_TYPE)));
        out.append(ret).append(' ').append(name);
        String sig = m.field(DeclarationFields.TYPE_PARAMS);
        if (sig != null && !sig.isEmpty()) {
            out.append(" /* generic signature: ").append(sig).append(" */");
        }
        out.append(renderParameterList(m));
        out.append(renderThrows(m));
        if ((a & Opcodes.ACC_ABSTRACT) != 0 || (a & Opcodes.ACC_NATIVE) != 0) {
            out.append(";\n");
        } else {
            out.append(" {}\n");
        }
    }

    private static String renderParameterList(IndexEntry m) {
        String args = nullToEmpty(m.field(DeclarationFields.ARG_TYPES));
        List<String> types = splitJvmTypes(args);
        int a = parseAccess(m);
        boolean varargs = (a & Opcodes.ACC_VARARGS) != 0;
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String t = types.get(i);
            if (varargs && i == types.size() - 1) {
                String javaT = toJavaType(t);
                if (javaT.endsWith("[]")) {
                    sb.append(javaT.substring(0, javaT.length() - 2)).append("... arg").append(i);
                } else {
                    sb.append(javaT).append(" arg").append(i);
                }
            } else {
                sb.append(toJavaType(t)).append(" arg").append(i);
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static String renderThrows(IndexEntry m) {
        String raw = nullToEmpty(m.field(DeclarationFields.THROWS_TYPES));
        if (raw.isEmpty()) {
            return "";
        }
        List<String> names =
                java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(IndexedSkeletonRenderer::toJavaType)
                        .collect(Collectors.toList());
        if (names.isEmpty()) {
            return "";
        }
        return " throws " + String.join(", ", names);
    }

    private static List<String> splitJvmTypes(String commaJoined) {
        List<String> out = new ArrayList<>();
        if (commaJoined == null || commaJoined.isEmpty()) {
            return out;
        }
        for (String part : commaJoined.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static void appendAnnotations(StringBuilder out, String serialized, String indent) {
        if (serialized == null || serialized.isEmpty()) {
            return;
        }
        for (String token : serialized.split(";")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            String at = annotationToSource(t);
            out.append(indent).append(at).append('\n');
        }
    }

    /** Descriptor like {@code Ljava/lang/Deprecated;} to {@code @java.lang.Deprecated}. */
    private static String annotationToSource(String desc) {
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return "@" + toJavaType(desc);
        }
        return "@" + desc;
    }

    private static String formatTypeList(String commaInternalNames) {
        return java.util.Arrays.stream(commaInternalNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(IndexedSkeletonRenderer::toJavaType)
                .collect(Collectors.joining(", "));
    }

    private static String typeKeyword(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return "@interface";
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return "enum";
        }
        if ((access & Opcodes.ACC_RECORD) != 0) {
            return "record";
        }
        return "class";
    }

    private static void appendTypeModifiers(StringBuilder out, int access, String typeKeyword) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            out.append("public ");
        } else if ((access & Opcodes.ACC_PROTECTED) != 0) {
            out.append("protected ");
        } else if ((access & Opcodes.ACC_PRIVATE) != 0) {
            out.append("private ");
        }
        if ((access & Opcodes.ACC_STATIC) != 0 && "@interface".equals(typeKeyword)) {
            // nested @interface
            out.append("static ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0
                && (access & Opcodes.ACC_INTERFACE) == 0
                && (access & Opcodes.ACC_ENUM) == 0) {
            out.append("final ");
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0
                && (access & Opcodes.ACC_INTERFACE) == 0
                && (access & Opcodes.ACC_ANNOTATION) == 0) {
            out.append("abstract ");
        }
        if ((access & Opcodes.ACC_STRICT) != 0) {
            out.append("strictfp ");
        }
    }

    private static String fieldModifiers(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            sb.append("public ");
        } else if ((access & Opcodes.ACC_PROTECTED) != 0) {
            sb.append("protected ");
        } else if ((access & Opcodes.ACC_PRIVATE) != 0) {
            sb.append("private ");
        }
        if ((access & Opcodes.ACC_STATIC) != 0) {
            sb.append("static ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            sb.append("final ");
        }
        if ((access & Opcodes.ACC_VOLATILE) != 0) {
            sb.append("volatile ");
        }
        if ((access & Opcodes.ACC_TRANSIENT) != 0) {
            sb.append("transient ");
        }
        return sb.toString();
    }

    private static String methodModifiers(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            sb.append("public ");
        } else if ((access & Opcodes.ACC_PROTECTED) != 0) {
            sb.append("protected ");
        } else if ((access & Opcodes.ACC_PRIVATE) != 0) {
            sb.append("private ");
        }
        if ((access & Opcodes.ACC_STATIC) != 0) {
            sb.append("static ");
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            sb.append("final ");
        }
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            sb.append("synchronized ");
        }
        if ((access & Opcodes.ACC_BRIDGE) != 0) {
            sb.append("/* bridge */ ");
        }
        if ((access & Opcodes.ACC_VARARGS) != 0 && (access & Opcodes.ACC_NATIVE) == 0) {
            // varargs shown on last parameter
        }
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            sb.append("native ");
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            sb.append("abstract ");
        }
        if ((access & Opcodes.ACC_STRICT) != 0) {
            sb.append("strictfp ");
        }
        return sb.toString();
    }

    static String toJavaType(String jvm) {
        if (jvm == null || jvm.isEmpty()) {
            return "";
        }
        char c = jvm.charAt(0);
        try {
            if (c == '[' || c == 'L') {
                return Type.getType(jvm).getClassName();
            }
            if (jvm.length() == 1) {
                return Type.getType(jvm).getClassName();
            }
            return Type.getObjectType(jvm).getClassName();
        } catch (Exception e) {
            return jvm.replace('/', '.');
        }
    }

    private static String packageName(String internal) {
        int slash = internal.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        return internal.substring(0, slash).replace('/', '.');
    }

    private static String simpleName(String internal) {
        int slash = internal.lastIndexOf('/');
        String tail = slash < 0 ? internal : internal.substring(slash + 1);
        int dollar = tail.lastIndexOf('$');
        return dollar < 0 ? tail : tail.substring(dollar + 1);
    }

    private static int parseAccess(IndexEntry e) {
        String s = e.field(DeclarationFields.ACCESS_FLAGS);
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
