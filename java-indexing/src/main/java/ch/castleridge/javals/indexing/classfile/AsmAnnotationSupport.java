package ch.castleridge.javals.indexing.classfile;

import org.objectweb.asm.tree.AnnotationNode;

import java.util.List;

final class AsmAnnotationSupport {

    private AsmAnnotationSupport() {}

    static String serializeAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        StringBuilder sb = new StringBuilder();
        appendList(sb, visible);
        if (invisible != null && !invisible.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            appendList(sb, invisible);
        }
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, List<AnnotationNode> list) {
        if (list == null) {
            return;
        }
        for (AnnotationNode an : list) {
            if (an == null || an.desc == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(an.desc);
        }
    }
}
