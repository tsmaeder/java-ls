/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis.ecj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddExports;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.AddReads;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.UpdateKind;
import org.eclipse.jdt.internal.compiler.env.IUpdatableModule.UpdatesByKind;
import org.eclipse.jdt.internal.compiler.util.SimpleSetOfCharArray;
import org.junit.jupiter.api.Test;

class EcjModuleUpdatesTest {

    @Test
    void emptyAndNullYieldEmptyMap() {
        assertTrue(EcjModuleUpdates.parse(null).isEmpty());
        assertTrue(EcjModuleUpdates.parse(List.of()).isEmpty());
        assertTrue(EcjModuleUpdates.parse(List.of("-source", "21", "-release", "21")).isEmpty());
    }

    @Test
    void parsesAddExportsTokenPair() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"));
        assertEquals(1, updates.size());
        UpdatesByKind byKind = updates.get("java.base");
        List<IUpdatableModule.AddExports> exports = packageUpdates(byKind);
        assertEquals(1, exports.size());
        assertEquals("jdk.internal.misc", String.valueOf(exports.get(0).getName()));
        assertEquals(1, exports.get(0).getTargetModules().length);
        assertEquals("ALL-UNNAMED", String.valueOf(exports.get(0).getTargetModules()[0]));
        assertTrue(byKind.getList(UpdateKind.MODULE, false).isEmpty());
    }

    @Test
    void parsesAddExportsEqualsFormAndMultipleTargets() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=m1,m2"));
        AddExports export = packageUpdates(updates.get("jdk.compiler")).get(0);
        assertEquals("com.sun.tools.javac.api", String.valueOf(export.getName()));
        assertEquals(2, export.getTargetModules().length);
        assertEquals("m1", String.valueOf(export.getTargetModules()[0]));
        assertEquals("m2", String.valueOf(export.getTargetModules()[1]));
    }

    @Test
    void parsesAddReadsTokenPairAndEqualsForm() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-reads", "app=java.sql",
                "--add-reads=lib=java.xml"));
        assertEquals(2, updates.size());
        AddReads app = moduleUpdates(updates.get("app")).get(0);
        assertEquals("java.sql", String.valueOf(app.getTarget()));
        AddReads lib = moduleUpdates(updates.get("lib")).get(0);
        assertEquals("java.xml", String.valueOf(lib.getTarget()));
    }

    @Test
    void multipleExportsOnSameModule() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-reads", "java.base=java.logging"));
        UpdatesByKind byKind = updates.get("java.base");
        assertEquals(2, packageUpdates(byKind).size());
        assertEquals(1, moduleUpdates(byKind).size());
    }

    @Test
    void skipsMalformedAndIgnoresUnrelatedOptions() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "-g",
                "--add-exports",
                "--add-exports", "not-a-valid-export",
                "--add-reads", "missing-equals",
                "--add-exports", "java.base/ok.pkg=ALL-UNNAMED",
                "--add-modules", "ALL-MODULE-PATH"));
        assertEquals(1, updates.size());
        assertEquals(1, packageUpdates(updates.get("java.base")).size());
    }

    @Test
    void applyModuleUpdatesInvokesConsumers() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-reads", "java.base=java.logging"));
        IndexNameEnvironment env = new IndexNameEnvironment(null, null, updates);
        RecordingModule module = new RecordingModule("java.base");

        env.applyModuleUpdates(module, UpdateKind.PACKAGE);
        assertEquals(1, module.exports.size());
        assertTrue(CharOperation.equals(
                "jdk.internal.misc".toCharArray(), module.exports.get(0).packageName()));
        assertEquals(0, module.reads.size());

        env.applyModuleUpdates(module, UpdateKind.MODULE);
        assertEquals(1, module.reads.size());
        assertTrue(CharOperation.equals("java.logging".toCharArray(), module.reads.get(0)));
    }

    @Test
    void applyModuleUpdatesSkipsUnnamedAndUnknownModules() {
        Map<String, UpdatesByKind> updates = EcjModuleUpdates.parse(List.of(
                "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"));
        IndexNameEnvironment env = new IndexNameEnvironment(null, null, updates);

        // Use the identity sentinel ECJ passes for the unnamed module.
        env.applyModuleUpdates(new IUpdatableModule() {
            @Override
            public char[] name() {
                return org.eclipse.jdt.internal.compiler.lookup.ModuleBinding.UNNAMED;
            }

            @Override
            public void addReads(char[] moduleName) {
                throw new AssertionError("unnamed must not be updated");
            }

            @Override
            public void addExports(char[] packageName, char[][] targetModules) {
                throw new AssertionError("unnamed must not be updated");
            }

            @Override
            public void setMainClassName(char[] mainClassName) {}

            @Override
            public void setPackageNames(SimpleSetOfCharArray packageNames) {}
        }, UpdateKind.PACKAGE);

        RecordingModule other = new RecordingModule("other");
        env.applyModuleUpdates(other, UpdateKind.PACKAGE);
        assertTrue(other.exports.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<AddExports> packageUpdates(UpdatesByKind byKind) {
        return (List<AddExports>) (List<?>) byKind.getList(UpdateKind.PACKAGE, false);
    }

    @SuppressWarnings("unchecked")
    private static List<AddReads> moduleUpdates(UpdatesByKind byKind) {
        return (List<AddReads>) (List<?>) byKind.getList(UpdateKind.MODULE, false);
    }

    private record ExportRecord(char[] packageName, char[][] targets) {}

    private static final class RecordingModule implements IUpdatableModule {
        private final char[] name;
        final List<ExportRecord> exports = new ArrayList<>();
        final List<char[]> reads = new ArrayList<>();

        RecordingModule(String name) {
            this.name = name.toCharArray();
        }

        @Override
        public char[] name() {
            return name;
        }

        @Override
        public void addReads(char[] moduleName) {
            reads.add(moduleName);
        }

        @Override
        public void addExports(char[] packageName, char[][] targetModules) {
            exports.add(new ExportRecord(packageName, targetModules));
        }

        @Override
        public void setMainClassName(char[] mainClassName) {}

        @Override
        public void setPackageNames(SimpleSetOfCharArray packageNames) {}
    }
}
