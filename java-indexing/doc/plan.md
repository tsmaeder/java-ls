# Java Indexing Infrastructure Build Plan

## General Rules

We reference resources like class files or source files by uri's: if they are on the file system by file uri's, if they are in a jar file, by an uri of the form "jar:file:/C:/Program%20Files/test.jar!/foo/bar.txt" like the JarURIConnection in the jdk uses.

## General Approach

1. We want to use an approach where we build indices independent of context: i.e. the index is built without regard to the classpath or modulepath.
2. We build an index of all declarations in all source and classfiles in a given set of files and directories. We do not index module-info.class or module-info.java files. We do not index package-info.java or package-info.class files. 
3. We then build a file manager based on ForwardingJavaFileManager which creates synthethic JavaFileObject instances of kind CLASS referencing the index entries.
4. We build a subclass of ClassReader that reads class files from synthetic file objects created in point 3. If the class file is not one of files created in step 3, we delegate to the regular ClassReader behaviour.

