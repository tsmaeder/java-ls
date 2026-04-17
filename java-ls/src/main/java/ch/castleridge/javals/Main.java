package ch.castleridge.javals;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.util.Context;

import ch.castleridge.javals.indexing.index.Index;
import ch.castleridge.javals.javac.IndexClassReader;
import ch.castleridge.javals.javac.IndexFileManager;

import static javax.tools.JavaFileObject.Kind.SOURCE;

/**
 * Small driver for experimenting with the index-backed compiler wiring.
 * Reads the source file given as the first argument, analyses it under a
 * javac task whose {@link javax.tools.JavaFileManager} is an
 * {@link IndexFileManager} layered over a standard file manager, and whose
 * {@code ClassReader} is swapped out for {@link IndexClassReader}.
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: Main <source-file>");
            System.exit(2);
        }

        JavacTool tool = JavacTool.create();
        Context context = new Context();

        Index index = new Index();
        IndexClassReader.preRegister(context, index);

        StandardJavaFileManager std = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
        IndexFileManager fileManager = new IndexFileManager(std, index);

        SimpleJavaFileObject input = new SimpleJavaFileObject(new File(args[0]).getAbsoluteFile().toURI(), SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return Files.readString(Path.of(this.uri));
            }
        };

        JavacTask task = (JavacTask) tool.getTask(
                new PrintWriter(System.out),
                fileManager,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Arrays.asList(input),
                context);

        try {
            task.analyze();
            for (CompilationUnitTree cu : task.parse()) {
                System.out.print(cu.toString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
