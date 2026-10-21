/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.indexing.source.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/**
 * Shared parse-only Kotlin PSI environment. Parsing is serialised on a lock
 * so the Scanner's parallel workers can safely call into it.
 */
final class KotlinPsiEnvironment {

    private static final Object LOCK = new Object();
    private static Disposable disposable;
    private static KotlinCoreEnvironment environment;
    private static KtPsiFactory psiFactory;

    private KotlinPsiEnvironment() {}

    static <T> T withParsed(String fileName, CharSequence content, Function<KtFile, T> action) {
        synchronized (LOCK) {
            ensureStarted();
            String name = fileName == null || fileName.isBlank() ? "Source.kt" : fileName;
            String text = content == null ? "" : content.toString();
            KtFile file = psiFactory.createFile(name, text);
            return action.apply(file);
        }
    }

    private static void ensureStarted() {
        if (environment != null) return;
        prepareIdeaHome();
        disposable = Disposer.newDisposable("java-ls-kotlin-index");
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "java-ls-kotlin-index");
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS,
                LanguageVersionSettingsImpl.DEFAULT);
        environment = KotlinCoreEnvironment.createForParallelTests(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
        psiFactory = new KtPsiFactory(environment.getProject());
    }

    /**
     * The embeddable IntelliJ core insists on an installation home with
     * {@code bin/idea.properties}. Point it at a throwaway directory when
     * the host process has none.
     */
    private static void prepareIdeaHome() {
        String existing = System.getProperty("idea.home.path");
        if (existing != null && !existing.isBlank()) {
            return;
        }
        try {
            Path home = Files.createTempDirectory("java-ls-idea-home");
            Path bin = home.resolve("bin");
            Files.createDirectories(bin);
            Files.writeString(bin.resolve("idea.properties"),
                    "# generated for kotlin-compiler-embeddable\n",
                    StandardCharsets.UTF_8);
            home.toFile().deleteOnExit();
            System.setProperty("idea.home.path", home.toAbsolutePath().toString());
            System.setProperty("idea.ignore.disabled.plugins", "true");
            System.setProperty("idea.use.native.fs.for.win", "false");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare idea.home.path for Kotlin PSI", e);
        }
    }
}
