package ch.castleridge.javals.javac;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

interface PackageRoot {
    Iterable<JavaFileObject> list(String packageName, Set<Kind> kinds, boolean recurse) throws IOException;
}

class JRTPackageRoot implements JavaFileManager.Location, PackageRoot {
    private final Path path;

    JRTPackageRoot(Path path) {
        this.path = path;
    }

    @Override
    public String toString() {
        return "JRTPackageRoot["+path.toString()+"]";
    }

    @Override
    public String getName() {
        return this.path.getFileName().toString();
    }

    @Override
    public boolean isOutputLocation() {
        return false;
    }

    @Override
    public boolean isModuleOrientedLocation() {
        return false;
    }

    @Override
    public Iterable<JavaFileObject> list(String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        return doList(this.path, "", packageName, kinds, recurse);
    }

    private Iterable<JavaFileObject> doList(Path root, String pathPackageName, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
        if (pathPackageName.equals(packageName)) {
            return collectFiles(root, kinds, recurse);
        }

        ArrayList<JavaFileObject> result = new ArrayList<>();

        try (Stream<Path> entries = Files.list(root)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                for (JavaFileObject file : doList(p, pathPackageName + '.' + p.getFileName().toString(), packageName, kinds, recurse)) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    private static Kind computeKind(String pathString) {
        if (pathString.endsWith(".java")) {
            return Kind.SOURCE;
        } else if (pathString.endsWith(".class")) {
            return Kind.CLASS;
        } else if (pathString.endsWith(".html")) {
            return Kind.HTML;
        } else {
            return Kind.OTHER;
        }
    }

    private Iterable<JavaFileObject> collectFiles(Path root, Set<Kind> kinds, boolean recurse) throws IOException {
        try (Stream<Path> fileStream = Files.walk(root, recurse ? Integer.MAX_VALUE : 1)) {
            var result = new ArrayList<JavaFileObject>();
            fileStream.filter(Files::isRegularFile).map(path -> {
                return new PathFileObject(path, computeKind(path.getFileName().toString()));
            }).filter(file -> {
                return kinds.contains(file.getKind());
            }).forEach(result::add);
            return result;
        }
    }
}

public class FileManager implements JavaFileManager {

    private final FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));

    private static final Set<Location> KNOWN_LOCATIONS = Set.of(
            StandardLocation.SOURCE_PATH,
            StandardLocation.SYSTEM_MODULES
    );

    @Override
    public int isSupportedOption(String option) {
        return -1;
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
        return getClass().getClassLoader();
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        System.out.println("listLocationsForModules " + location.toString());

        if (StandardLocation.SYSTEM_MODULES.equals(location)) {
            Path modulesPath = fs.getPath("modules");
            try (Stream<Path> pathStream = Files.list(modulesPath)) {
                return Collections.singleton((pathStream.map(JRTPackageRoot::new).collect(Collectors.toSet())));
            }
        }
        return Collections.emptyList();
    }


    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse)
            throws IOException {
        System.out.println("list: " + location + " " + packageName + " " + kinds + " " + recurse);
        if (location instanceof PackageRoot) {
            return ((PackageRoot) location).list(packageName, kinds, recurse);
        }
        return new ArrayList<>();
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        System.out.println("Inferring binary name for "+file.toString()+ "in "+location.toString());
        return null;
    }

    @Override
    public String inferModuleName(Location location) throws IOException {
        return location.getName();
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return false;
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return true;
    }

    @Override
    public boolean hasLocation(Location location) {
        return FileManager.KNOWN_LOCATIONS.contains(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
        System.out.println("getJavaFileForInput  " + location.toString() + " " + className);
        return null;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling)
            throws IOException {

        return null;
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return null;
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling)
            throws IOException {
        return null;
    }

    @Override
    public void flush() throws IOException {

    }

    @Override
    public void close() throws IOException {

    }

}
