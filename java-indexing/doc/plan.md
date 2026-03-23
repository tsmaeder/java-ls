# Java Indexing Infrastructure Build Plan

## General Rules

We reference resources like class files or source files by uri's: if they are on the file system by file uri's, if they are in a jar file, by an uri of the form "jar:file:/C:/Program%20Files/test.jar!/foo/bar.txt" like the JarURIConnection in the jdk uses.

## Index

And index is a set of entries containing a set of named fields where we can insert and delete entries. Updating indices is done by deleting and reinserting. We can read a subset of the entries by searching for a combination of field queries, where a field query is a substring query for the field value of the entry. We can delete a set of entries selected by specifying a field query of all files to be deleted.
All methods in the index API should be async and support streaming where it makes sense.

## General Approach

We want to use an approach where we build indices independent of context: i.e. we do not need to know the build path (which jars and source files are visible). For that we need to maintain various indices:

### Declaration indices

1. An index of the externally visible symbols (types, methods, fields) for each class file.
2. And index of the externally visible symbols of source files.

For each declared class or interface type, we need to record its JVM name, the type parameters, it's extends and implements clauses and the type annotations
For each method, we need to record the name, the JVM name of its owner type,  its return type, type parameters, argument types and throws types and the method annotations
For each field, we need to record it's name, the JVM name of its owner type, its declared type and its annotations
We want to store type, field and method declarations in the same storage structure, differentiated by a "type" flag and null values where a field does not apply.

### Reference indices

1. An index of field references by simple identifier and access type (read/invoke)
2. An index of method references by simple identifier and access type (read/write)
3. An index of type references by fully qualified identifier. If the fully qualified identifier cannot be determined, we add a reference to all possible fq types (i.e. add a ref for all * imported packages)