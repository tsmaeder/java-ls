package ch.castleridge.javals.indexing.classfile;

import ch.castleridge.javals.indexing.declaration.DeclarationIndex;
import ch.castleridge.javals.indexing.store.IndexEntry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class AsmClassDeclarationExtractor implements ClassDeclarationExtractor {

    @Override
    public List<IndexEntry> extract(URI resourceUri, byte[] classBytes) {
        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        List<IndexEntry> out = new ArrayList<>();

        String extendsJvm = cn.superName != null ? cn.superName : "";
        String implementsJvm =
                cn.interfaces == null || cn.interfaces.isEmpty()
                        ? ""
                        : String.join(",", cn.interfaces);
        String typeParams = cn.signature != null ? cn.signature : "";
        String annos = AsmAnnotationSupport.serializeAnnotations(cn.visibleAnnotations, cn.invisibleAnnotations);

        out.add(
                DeclarationIndex.typeRow(
                        resourceUri, cn.name, typeParams, extendsJvm, implementsJvm, annos));

        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                String fAnn =
                        AsmAnnotationSupport.serializeAnnotations(fn.visibleAnnotations, fn.invisibleAnnotations);
                String declared = AsmTypeStrings.jvmForm(Type.getType(fn.desc));
                out.add(
                        DeclarationIndex.fieldRow(
                                resourceUri, cn.name, fn.name, fn.desc, declared, fAnn));
            }
        }

        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                String mAnn =
                        AsmAnnotationSupport.serializeAnnotations(mn.visibleAnnotations, mn.invisibleAnnotations);
                String throwsJvm =
                        mn.exceptions == null || mn.exceptions.isEmpty()
                                ? ""
                                : String.join(",", mn.exceptions);
                String sig = mn.signature != null ? mn.signature : "";
                out.add(
                        DeclarationIndex.methodRow(
                                resourceUri,
                                cn.name,
                                mn.name,
                                mn.desc,
                                sig,
                                AsmTypeStrings.returnTypeJvm(mn.desc),
                                AsmTypeStrings.argTypesJoined(mn.desc),
                                throwsJvm,
                                mAnn));
            }
        }

        return out;
    }
}
