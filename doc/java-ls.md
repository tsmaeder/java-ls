# Java LS: a Polyglot Language Server for Java that Scales

## tl;dr

The basic idea of Java LS is to precompile the sources and class files in a workpace to an in-memory index of symbol declarations.
When we need to resolve symbols in order to implement LSP features (like "go to declaration"), we replace the mechanism
for loading class files in the compiler to resolve symbols from the index instead of loading class or source files.
This approach has proven to be fast at cold startup and performs well as far as speed and memory consumption is
concerned. A workspace with the Spring Boot source code (ca 8500 source files and around 1000 jars, 430k types) is
indexed in less than 35 seconds and references to the class "String" (> 20k occurrences in ~4k files) can be computed 
in less than a minute. Since the index is based on JVM class types, the approach can be extended to work for other JVM languages, as well.

## Declaration Indexing

The crucial design decision in Java LS is to make the indexing "context free". I.e. when we index a file, we never
resolve any of the symbols in the file. In JavaC terms, we just run the "parse" phase of the compiler, not the "analyze"
phase.
This has multiple advantages: For one, parsing is fast and scales linearly with the total size of the input workspace.
The parsing can also be done in parallel and in any order. Updating the index is trivial, as there are no dependencies
between Index entries. As the index is only dependent on the input files, you can precompute it and just load if from
a file. In a shared environment like an cloud workspace system you could have a shared server for the index entries, just
applying the classpath at resolution time. 

## Build Path and Symbol Resolution

When we open a file in the IDE, the language server runs the "parse()" and "analyze" phases of the compiler. The
result of this analysis is used to show errors, for example, or to navigate to the declaration of a symbol.
When the compiler tries to resolve a symbol mentioned in the source that is not defined in the source file, it
tries to locate the symbol on its build path. We replace the ClassReader and JavaFileManager objects in the
compiler in order to resolve these symbols from the declaration index instead of from disk. This symbol resolution
process uses the correct build path to determine which symbols are visible to the compiler in the given file.

## Cross Referencing

When we want to implement LSP features like "find references", we want to compute precise, semantic references. For
example, when we want to compute references to the method "MyClass.foo()", we do not want to find references to a
method "foo()" that might be declared on another class "YourClass.foo()". In order to to that, we need to analyze
any source file that may contain references to "foo" and make sure the identifier "foo" refers to the right
method on the right class.
Our approach in Java LS for computing references it this: when indexing sources, we also build a bloom filter for
all identifiers used in a source file. This bloom filter will give us all files containing a certain identifier
("foo") with some false positives. We then run the "analyze()" phase of the compiler to find the precise matches
to the symbol we are looking for in those candidate files.

## Polyglot LS

Java LS could be extended to include multiple source languages quite easily: since the index has the shape of JVM
objects, all we need is a component that can translate the source languag into its JVM representation. On the other
end of things, we need to replace the mechanism that loads class files for the compiler from the index. Since all
JVM-based language need to be able to load class files (how else would they handle java/lang/Object, for example),
such a mechanism must exist in all languages. In order to compute polyglot symbol references, one would need a component
that computes references to a JVM symbol in a source file of the given language.