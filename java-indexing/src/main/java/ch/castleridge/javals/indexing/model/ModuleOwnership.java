/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.model;

/**
 * Shared package-ownership checks for indexed {@link ModuleEntry}s, used by
 * both the javac file manager and the ECJ name environment.
 */
public final class ModuleOwnership {

    private ModuleOwnership() {}

    /**
     * True when {@code module} declares {@code packageJvm} via its package
     * set, an exports directive, or an opens directive (exact match).
     */
    public static boolean ownsPackage(ModuleEntry module, String packageJvm) {
        if (module == null || packageJvm == null) {
            return false;
        }
        if (containsPackage(module.packages(), packageJvm)) {
            return true;
        }
        for (ModuleEntry.Exports e : module.exports()) {
            if (e.packageJvm().equals(packageJvm)) {
                return true;
            }
        }
        for (ModuleEntry.Opens o : module.opens()) {
            if (o.packageJvm().equals(packageJvm)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code module} declares {@code packageJvm} or any nested
     * package under it. Needed so {@code isPackage} can see intermediate
     * parents like {@code java} when only {@code java/lang} is listed.
     */
    public static boolean declaresPackageOrParent(ModuleEntry module, String packageJvm) {
        if (module == null || packageJvm == null) {
            return false;
        }
        if (ownsPackage(module, packageJvm)) {
            return true;
        }
        if (isPrefixOfDeclared(module.packages(), packageJvm)) {
            return true;
        }
        for (ModuleEntry.Exports e : module.exports()) {
            if (isPrefix(packageJvm, e.packageJvm())) {
                return true;
            }
        }
        for (ModuleEntry.Opens o : module.opens()) {
            if (isPrefix(packageJvm, o.packageJvm())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPackage(String[] packages, String packageJvm) {
        for (String p : packages) {
            if (p.equals(packageJvm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrefixOfDeclared(String[] packages, String packageJvm) {
        for (String p : packages) {
            if (isPrefix(packageJvm, p)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code prefix} is {@code full} or a slash-separated parent. */
    private static boolean isPrefix(String prefix, String full) {
        if (full == null) {
            return false;
        }
        if (prefix.isEmpty()) {
            return !full.isEmpty();
        }
        return full.equals(prefix) || full.startsWith(prefix + "/");
    }

    /** Declaring package of a JVM binary name ({@code pkg/Type} → {@code pkg}). */
    public static String packageOf(String jvmName) {
        if (jvmName == null || jvmName.isEmpty()) {
            return "";
        }
        int slash = jvmName.lastIndexOf('/');
        return slash < 0 ? "" : jvmName.substring(0, slash);
    }
}
