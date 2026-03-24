package ch.castleridge.javals.indexing.declaration;

import ch.castleridge.javals.indexing.store.IndexEntry;
import ch.castleridge.javals.indexing.store.IndexStore;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Typed facade over {@link IndexStore} for type/method/field declaration rows. */
public final class DeclarationIndex {

    public static final String KIND_TYPE = "TYPE";
    public static final String KIND_METHOD = "METHOD";
    public static final String KIND_FIELD = "FIELD";

    private final IndexStore store;

    public DeclarationIndex(IndexStore store) {
        this.store = store;
    }

    public IndexStore store() {
        return store;
    }

    public CompletableFuture<Void> insertAll(List<IndexEntry> entries) {
        return store.insertAll(entries);
    }

    /** Removes all declaration rows for the given class file URI. */
    public CompletableFuture<Long> removeForResource(URI resourceUri) {
        return store.removePartition(resourceUri.toString());
    }

    public static IndexEntry typeRow(
            URI resourceUri,
            String jvmName,
            String typeParams,
            String extendsJvm,
            String implementsJvm,
            String annotations,
            int accessFlags) {
        Map<String, String> m = baseRow(resourceUri, KIND_TYPE, jvmName, accessFlags);
        m.put(DeclarationFields.TYPE_PARAMS, nullToEmpty(typeParams));
        m.put(DeclarationFields.EXTENDS, nullToEmpty(extendsJvm));
        m.put(DeclarationFields.IMPLEMENTS, nullToEmpty(implementsJvm));
        m.put(DeclarationFields.ANNOTATIONS, nullToEmpty(annotations));
        m.put(DeclarationFields.MEMBER_NAME, "");
        m.put(DeclarationFields.DESCRIPTOR, "");
        m.put(DeclarationFields.RETURN_TYPE, "");
        m.put(DeclarationFields.ARG_TYPES, "");
        m.put(DeclarationFields.THROWS_TYPES, "");
        m.put(DeclarationFields.DECLARED_TYPE, "");
        return new IndexEntry(m);
    }

    public static IndexEntry methodRow(
            URI resourceUri,
            String ownerJvmName,
            String memberName,
            String descriptor,
            String typeParams,
            String returnTypeJvm,
            String argTypesJvm,
            String throwsJvm,
            String annotations,
            int accessFlags) {
        Map<String, String> m = baseRow(resourceUri, KIND_METHOD, ownerJvmName, accessFlags);
        m.put(DeclarationFields.MEMBER_NAME, memberName);
        m.put(DeclarationFields.DESCRIPTOR, nullToEmpty(descriptor));
        m.put(DeclarationFields.TYPE_PARAMS, nullToEmpty(typeParams));
        m.put(DeclarationFields.RETURN_TYPE, nullToEmpty(returnTypeJvm));
        m.put(DeclarationFields.ARG_TYPES, nullToEmpty(argTypesJvm));
        m.put(DeclarationFields.THROWS_TYPES, nullToEmpty(throwsJvm));
        m.put(DeclarationFields.ANNOTATIONS, nullToEmpty(annotations));
        m.put(DeclarationFields.EXTENDS, "");
        m.put(DeclarationFields.IMPLEMENTS, "");
        m.put(DeclarationFields.DECLARED_TYPE, "");
        return new IndexEntry(m);
    }

    public static IndexEntry fieldRow(
            URI resourceUri,
            String ownerJvmName,
            String fieldName,
            String descriptor,
            String declaredTypeJvm,
            String annotations,
            int accessFlags) {
        Map<String, String> m = baseRow(resourceUri, KIND_FIELD, ownerJvmName, accessFlags);
        m.put(DeclarationFields.MEMBER_NAME, fieldName);
        m.put(DeclarationFields.DESCRIPTOR, nullToEmpty(descriptor));
        m.put(DeclarationFields.DECLARED_TYPE, nullToEmpty(declaredTypeJvm));
        m.put(DeclarationFields.ANNOTATIONS, nullToEmpty(annotations));
        m.put(DeclarationFields.TYPE_PARAMS, "");
        m.put(DeclarationFields.EXTENDS, "");
        m.put(DeclarationFields.IMPLEMENTS, "");
        m.put(DeclarationFields.RETURN_TYPE, "");
        m.put(DeclarationFields.ARG_TYPES, "");
        m.put(DeclarationFields.THROWS_TYPES, "");
        return new IndexEntry(m);
    }

    private static Map<String, String> baseRow(URI resourceUri, String kind, String jvmName, int accessFlags) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DeclarationFields.RESOURCE_URI, resourceUri.toString());
        m.put(DeclarationFields.KIND, kind);
        m.put(DeclarationFields.JVM_NAME, jvmName);
        m.put(DeclarationFields.ACCESS_FLAGS, Integer.toString(accessFlags));
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
