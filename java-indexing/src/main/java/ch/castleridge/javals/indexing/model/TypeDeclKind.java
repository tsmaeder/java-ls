package ch.castleridge.javals.indexing.model;

/**
 * Declaration kind of an indexed type. Set from source AST for
 * source-derived entries; {@link #UNKNOWN} for bytecode-derived entries
 * where kind is not stored separately.
 */
public enum TypeDeclKind {
    UNKNOWN,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION
}
