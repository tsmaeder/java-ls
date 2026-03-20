package ch.castleridge.javals;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.Tool;
import javax.tools.ToolProvider;

import ch.castleridge.javals.javac.FileManager;

import static javax.tools.JavaFileObject.Kind.SOURCE;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

public class Main {
    
    public static void main(String[] args) {
        FileManager fileManager = new FileManager();
        SimpleJavaFileObject input = new SimpleJavaFileObject(new File(args[0]).getAbsoluteFile().toURI(), SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return Files.readString(Path.of(this.uri));
            }
        };

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavacTask task = (JavacTask) compiler.getTask(new PrintWriter(System.out), fileManager, null,
                Collections.emptyList(), Collections.emptyList(), Arrays.asList(input));

        try {
            task.analyze();
            for (CompilationUnitTree cu: task.parse()) {
                Trees t = Trees.instance(task);
                System.out.print(cu.toString());
            }

        } catch(IOException e) {
            e.printStackTrace();
        }
    }
}
