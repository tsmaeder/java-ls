package ch.castleridge.javals.javac;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

import com.sun.tools.javac.api.ClientCodeWrapper;

import ch.castleridge.javals.indexing.index.AbstractJavaFileObject;
import ch.castleridge.javals.indexing.model.ModuleEntry;

/**
 * Synthetic {@link javax.tools.JavaFileObject} that materialises a
 * {@code module-info.class} from a {@link ModuleEntry}.
 *
 * <p>Unlike {@link IndexClassFileObject} we cannot bypass bytecode reading
 * for modules: javac's {@code ClassReader} carries module-specific
 * post-processing (directive list normalisation, package set wiring,
 * sealing checks) that we don't want to duplicate. So instead we emit a
 * faithful {@code module-info.class} on demand using ASM and let
 * {@code ClassReader.readModule} populate the {@link
 * com.sun.tools.javac.code.Symbol.ModuleSymbol} the normal way.
 *
 * <p>Bytes are computed lazily on the first {@link #openInputStream()}
 * call and cached for the lifetime of the file object.
 */
@ClientCodeWrapper.Trusted
public final class IndexModuleFileObject extends AbstractJavaFileObject {

    private static final int CLASS_VERSION = Opcodes.V11;

    private final ModuleEntry entry;
    private volatile byte[] bytes;

    public IndexModuleFileObject(ModuleEntry entry) {
        super(uriFor(entry), Kind.CLASS);
        this.entry = entry;
    }

    public ModuleEntry entry() {
        return entry;
    }

    public String moduleName() {
        return entry.name();
    }

    @Override
    public String getName() {
        return entry.name() + "/module-info.class";
    }

    @Override
    public boolean isNameCompatible(String simpleName, Kind kind) {
        return kind == Kind.CLASS && "module-info".equals(simpleName);
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return new ByteArrayInputStream(bytes());
    }

    /**
     * Lazily synthesise the {@code module-info.class} bytes from {@link #entry}.
     * Result is cached after the first compute so repeated reads (javac
     * may revisit a module during the analyse phase) share allocations.
     */
    public byte[] bytes() {
        byte[] local = bytes;
        if (local != null) return local;
        synchronized (this) {
            if (bytes != null) return bytes;
            ClassWriter cw = new ClassWriter(0);
            cw.visit(CLASS_VERSION, Opcodes.ACC_MODULE, "module-info", null, null, null);
            ModuleVisitor mv = cw.visitModule(entry.name(), entry.flags(), entry.version());
            if (mv != null) {
                if (entry.mainClass() != null) {
                    mv.visitMainClass(entry.mainClass());
                }
                for (String p : entry.packages()) {
                    mv.visitPackage(p);
                }
                for (ModuleEntry.Requires r : entry.requires()) {
                    mv.visitRequire(r.moduleName(), r.flags(), r.version());
                }
                for (ModuleEntry.Exports e : entry.exports()) {
                    mv.visitExport(e.packageJvm(), e.flags(), e.toModules());
                }
                for (ModuleEntry.Opens o : entry.opens()) {
                    mv.visitOpen(o.packageJvm(), o.flags(), o.toModules());
                }
                for (String u : entry.uses()) {
                    mv.visitUse(u);
                }
                for (ModuleEntry.Provides p : entry.provides()) {
                    mv.visitProvide(p.serviceJvm(), p.implJvms());
                }
                mv.visitEnd();
            }
            cw.visitEnd();
            bytes = cw.toByteArray();
            return bytes;
        }
    }

    private static URI uriFor(ModuleEntry entry) {
        if (entry.resourceUri() != null) {
            return URI.create(entry.resourceUri());
        }
        return URI.create("index:///" + entry.name() + "/module-info.class");
    }
}
