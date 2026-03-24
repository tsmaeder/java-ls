package ch.castleridge.javals.indexing.declaration;

/** Field names for declaration index documents (SQL column names can mirror these). */
public final class DeclarationFields {
    public static final String RESOURCE_URI = "resourceUri";
    /** TYPE, METHOD, or FIELD */
    public static final String KIND = "kind";
    /** Internal JVM name of the class (owner for members, this type for TYPE rows). */
    public static final String JVM_NAME = "jvmName";
    /** Simple method or field name; empty for TYPE rows. */
    public static final String MEMBER_NAME = "memberName";
    public static final String DESCRIPTOR = "descriptor";
    public static final String TYPE_PARAMS = "typeParams";
    public static final String EXTENDS = "extendsJvm";
    public static final String IMPLEMENTS = "implementsJvm";
    public static final String RETURN_TYPE = "returnTypeJvm";
    public static final String ARG_TYPES = "argTypesJvm";
    public static final String THROWS_TYPES = "throwsJvm";
    public static final String DECLARED_TYPE = "declaredTypeJvm";
    public static final String ANNOTATIONS = "annotations";
    /** Bitmask string from ASM access flags (see {@link org.objectweb.asm.Opcodes}). */
    public static final String ACCESS_FLAGS = "accessFlags";

    private DeclarationFields() {}
}
