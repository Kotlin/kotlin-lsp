// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.java.workspace.entities.JavaModuleCompilerOptionsEntity
import com.intellij.ls.server.importing.substituteModuleDependencies
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.workspaceModel.ide.impl.createIdeVirtualFileUrlManager
import com.jetbrains.ls.imports.json.ContentRootData
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.JSON_EXTERNAL_SYSTEM_ID
import com.jetbrains.ls.imports.json.JavaSettingsData
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.SdkRootData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.XmlElement
import com.jetbrains.ls.imports.json.flattenExportedDependencies
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.workspaceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonImporterUnitTest {
    @Test
    fun placeholderJdkHomeImportsWithEmptyRoots(@TempDir workspacePath: Path) {
        val data = WorkspaceData(
            sdks = listOf(
                SdkData(name = "Java SDK", type = "JavaSDK", version = null, homePath = "<JDK_HOME>", additionalData = ""),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))

        val sdk = storage.entities<SdkEntity>().single()
        assertTrue(sdk.roots.isEmpty(), "Placeholder JDK home must not be resolved to SDK roots")
        assertNull(sdk.homePath, "Placeholder JDK home must not be stored as a path")
    }

    @Test
    fun legacyJrtSdkRootsDropTheModulesSegment(@TempDir workspacePath: Path) {
        val data = WorkspaceData(
            sdks = listOf(
                SdkData(
                    name = "Java SDK", type = "JavaSDK", version = null, homePath = null, additionalData = "",
                    roots = listOf(
                        SdkRootData(url = "jrt:///jdk!/modules/java.base", type = "CLASSES"),
                        SdkRootData(url = "jrt:///jdk!/java.desktop", type = "CLASSES"),
                        SdkRootData(url = "jar:///jdk/lib/src.zip!/modules/java.base", type = "SOURCES"),
                    ),
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))

        val roots = storage.entities<SdkEntity>().single().roots.map { it.url.url }
        assertEquals(
            listOf("jrt:///jdk!/java.base", "jrt:///jdk!/java.desktop", "jar:///jdk/lib/src.zip!/modules/java.base"),
            roots,
            "A legacy jrt root drops the modules segment; new-shape jrt and jar roots stay verbatim",
        )
    }

    /**
     * Graph: A -> B (not exported); B -> C (exported) and B -> lib L (exported); C -> lib M (exported).
     * After flattening, A must directly see C (exported via B) and the libraries L and M reachable through
     * the exported chain, so the analyzer's non-recursive enumerator resolves them.
     */
    @Test
    fun flattensTransitivelyExportedModulesAndLibraries(@TempDir workspacePath: Path) {
        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "A",
                    dependencies = listOf(
                        DependencyData.Module(name = "B", scope = DependencyDataScope.COMPILE, isExported = false),
                    ),
                ),
                ModuleData(
                    name = "B",
                    dependencies = listOf(
                        DependencyData.Module(name = "C", scope = DependencyDataScope.COMPILE, isExported = true),
                        DependencyData.Library(name = "L", scope = DependencyDataScope.COMPILE, isExported = true),
                    ),
                ),
                ModuleData(
                    name = "C",
                    dependencies = listOf(
                        DependencyData.Library(name = "M", scope = DependencyDataScope.COMPILE, isExported = true),
                    ),
                ),
            ),
            libraries = listOf(
                LibraryData(name = "L", type = null, roots = listOf(LibraryRootData(path = "lib/l.jar"))),
                LibraryData(name = "M", type = null, roots = listOf(LibraryRootData(path = "lib/m.jar"))),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))

        flattenExportedDependencies(storage)

        val a = workspaceData(storage, workspacePath).modules.first { it.name == "A" }
        val moduleDeps = a.dependencies.filterIsInstance<DependencyData.Module>().map { it.name }.toSet()
        val libraryDeps = a.dependencies.filterIsInstance<DependencyData.Library>().map { it.name }.toSet()

        assertEquals(setOf("B", "C"), moduleDeps, "A should keep B and gain transitively-exported C")
        assertEquals(setOf("L", "M"), libraryDeps, "A should gain libraries reachable via the exported chain")
    }

    @Test
    fun recordsExternalProjectPathAsLinkedProjectPath(@TempDir workspacePath: Path) {
        // A Gradle-like source-set module: its content root is a source directory, but the external project path
        // is the owning subproject. The linked project path must follow the external project path.
        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "app.main",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/app/src/main")),
                    externalProjectPath = "<WORKSPACE>/app",
                ),
                // No external project path: the linked project path falls back to the module's content root.
                ModuleData(
                    name = "lib",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/lib")),
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(
            data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "GRADLE",
        )

        val modules = storage.entities<ModuleEntity>().associateBy { it.name }
        assertEquals(
            workspacePath.resolve("app").toString(),
            modules.getValue("app.main").exModuleOptions?.linkedProjectPath,
            "source-set module should use its external project path as the linked project path",
        )
        assertEquals(
            workspacePath.resolve("lib").toString(),
            modules.getValue("lib").exModuleOptions?.linkedProjectPath,
            "module without an external project path should fall back to its content root",
        )
    }

    @Test
    fun externalProjectPathSurvivesExportReimportRoundTrip(@TempDir workspacePath: Path) {
        // `exportWorkspace` serializes the materialized model back to workspace.json, which is then re-imported
        // on the next open. The external project path (a Gradle source-set module's owning subproject) must survive
        // that round-trip, otherwise the linked project path regresses to the source-directory content root.
        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "app.main",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/app/src/main")),
                    externalProjectPath = "<WORKSPACE>/app",
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(
            data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "GRADLE",
        )

        // Export the model back to the JSON data classes.
        val exported = workspaceData(storage, workspacePath).modules.single { it.name == "app.main" }
        assertEquals(
            "<WORKSPACE>/app",
            exported.externalProjectPath,
            "export must preserve the external project path",
        )

        // Re-import the exported data the way JsonWorkspaceImporter does (externalSystemId = "JSON").
        val reimported = MutableEntityStorage.create()
        reimported.importWorkspaceData(
            WorkspaceData(modules = listOf(exported)),
            workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "JSON",
        )
        assertEquals(
            workspacePath.resolve("app").toString(),
            reimported.entities<ModuleEntity>().single { it.name == "app.main" }.exModuleOptions?.linkedProjectPath,
            "linked project path must still point at the external project directory after a round-trip",
        )
    }

    @Test
    fun externalProjectIdSurvivesExportReimportRoundTrip(@TempDir workspacePath: Path) {
        // The Gradle project path is what a build-tool launch invokes its task on, and it is *not* derivable from the
        // module's directories: this project is called `:core` but lives in `modules/core`. It has to reach the
        // workspace model as the importer recorded it, and survive the export/reimport that happens on the next open.
        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "core.main",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/modules/core/src/main")),
                    externalProjectPath = "<WORKSPACE>/modules/core",
                    externalProjectId = ":core",
                ),
                // A module the importer could not attribute keeps no id, so a launch declines it rather than guessing.
                ModuleData(
                    name = "unattributed",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/unattributed")),
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(
            data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "GRADLE",
        )
        val imported = storage.entities<ModuleEntity>().associateBy { it.name }
        assertEquals(":core", imported.getValue("core.main").exModuleOptions?.linkedProjectId)
        assertNull(imported.getValue("unattributed").exModuleOptions?.linkedProjectId)

        val exported = workspaceData(storage, workspacePath).modules.single { it.name == "core.main" }
        assertEquals(":core", exported.externalProjectId, "export must preserve the external project id")

        val reimported = MutableEntityStorage.create()
        reimported.importWorkspaceData(
            WorkspaceData(modules = listOf(exported)),
            workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "JSON",
        )
        assertEquals(
            ":core",
            reimported.entities<ModuleEntity>().single { it.name == "core.main" }.exModuleOptions?.linkedProjectId,
            "the Gradle project path must still be the one Gradle reported after a round-trip",
        )
    }

    /**
     * Which build system imported the workspace is what every build-tool feature keys off — whether a launch goes
     * through Gradle, and which compile invocation `resolveBuildCommand` answers with. `exportWorkspace` writes the
     * model back to workspace.json, and on the next open [com.jetbrains.ls.imports.json.JsonWorkspaceImporter] reads
     * that file: it must not relabel a Gradle workspace as its own file format.
     *
     * Regression: it did exactly that, so exporting a Gradle project (or opening one whose workspace.json was
     * checked in) silently turned every Gradle launch into a direct `java` one and made the build step report
     * "Build before launch is not supported for build tool 'JSON'".
     */
    @Test
    fun externalSystemSurvivesExportReimportRoundTrip(@TempDir workspacePath: Path) {
        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "app.main",
                    contentRoots = listOf(ContentRootData(path = "<WORKSPACE>/app/src/main")),
                    externalProjectPath = "<WORKSPACE>/app",
                    externalProjectId = ":app",
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(
            data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true), externalSystemId = "GRADLE",
        )

        val exported = workspaceData(storage, workspacePath)
        assertEquals("GRADLE", exported.externalSystem, "export must record which build system produced the model")

        // Re-import the way JsonWorkspaceImporter does: it passes its own id, which must not win over the recorded one.
        val reimported = MutableEntityStorage.create()
        reimported.importWorkspaceData(
            exported, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true),
            externalSystemId = JSON_EXTERNAL_SYSTEM_ID,
        )
        assertEquals(
            "GRADLE",
            reimported.entities<ModuleEntity>().single().exModuleOptions?.externalSystem,
            "the module must still belong to Gradle after a round-trip, or every Gradle launch turns into a plain java one",
        )
    }

    /**
     * A workspace.json that records no build system is just that — the importer's own id is then the best answer, and
     * the marker it leaves is not itself a build system: exporting must not turn `"JSON"` into one, or a JPS project
     * would come back from a round trip claiming to be built by its own file format (and the round trip would stop
     * being one).
     */
    @Test
    fun theJsonImportersOwnMarkerIsNotRecordedAsABuildSystem(@TempDir workspacePath: Path) {
        val data = WorkspaceData(modules = listOf(ModuleData(name = "A")))

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(
            data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true),
            externalSystemId = JSON_EXTERNAL_SYSTEM_ID,
        )
        assertEquals(JSON_EXTERNAL_SYSTEM_ID, storage.entities<ModuleEntity>().single().exModuleOptions?.externalSystem)
        assertNull(workspaceData(storage, workspacePath).externalSystem, "the file format is not a build system")

        // A pure-JPS workspace has no external system at all, and must not acquire one.
        val jps = MutableEntityStorage.create()
        jps.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))
        assertNull(jps.entities<ModuleEntity>().single().exModuleOptions)
        assertNull(workspaceData(jps, workspacePath).externalSystem)
    }

    @Test
    fun importsPerModuleJavacArgumentsIntoWorkspaceModel(@TempDir workspacePath: Path) {
        val args = listOf("--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED", "-parameters")
        val data = WorkspaceData(
            modules = listOf(ModuleData(name = "A", dependencies = emptyList())),
            javaSettings = listOf(
                JavaSettingsData(
                    module = "A",
                    inheritedCompilerOutput = true,
                    excludeOutput = true,
                    compilerOutputs = emptyList(),
                    compilerOutputsForTests = emptyList(),
                    languageLevelId = null,
                    manifestAttributes = emptyMap(),
                    compilerArguments = args,
                )
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))

        val optionsEntity = storage.entities<JavaModuleCompilerOptionsEntity>().single()
        assertEquals("A", optionsEntity.module.name)
        assertEquals(args, optionsEntity.additionalOptions)

        // ...and the arguments round-trip back to the JSON model on export.
        val exported = workspaceData(storage, workspacePath).javaSettings.single { it.module == "A" }
        assertEquals(args, exported.compilerArguments)
    }

    /**
     * Module `consumer` depends on the library `com.example:lib:1.0`, which is also produced by the imported
     * module `lib` (its coordinate). After substitution `consumer` must depend on module `lib` instead of the
     * library, while the unrelated library `com.other:other:2.0` stays a library dependency.
     */
    @Test
    fun substitutesLibraryDependencyWithProducingModule(@TempDir workspacePath: Path) {
        fun mavenProperties(group: String, artifact: String, version: String) = XmlElement(
            tag = "properties",
            attributes = mapOf("groupId" to group, "artifactId" to artifact, "version" to version, "baseVersion" to version),
        )

        val data = WorkspaceData(
            modules = listOf(
                ModuleData(
                    name = "lib",
                    coordinate = "com.example:lib:1.0",
                ),
                ModuleData(
                    name = "consumer",
                    dependencies = listOf(
                        DependencyData.Library(name = "Maven: com.example:lib:1.0", scope = DependencyDataScope.COMPILE),
                        DependencyData.Library(name = "Maven: com.other:other:2.0", scope = DependencyDataScope.COMPILE),
                    ),
                ),
            ),
            libraries = listOf(
                LibraryData(
                    name = "Maven: com.example:lib:1.0",
                    type = "repository",
                    roots = listOf(LibraryRootData(path = "lib/example.jar")),
                    properties = mavenProperties("com.example", "lib", "1.0"),
                ),
                LibraryData(
                    name = "Maven: com.other:other:2.0",
                    type = "repository",
                    roots = listOf(LibraryRootData(path = "lib/other.jar")),
                    properties = mavenProperties("com.other", "other", "2.0"),
                ),
            ),
        )

        val storage = MutableEntityStorage.create()
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, createIdeVirtualFileUrlManager(true))

        substituteModuleDependencies(storage)

        val consumer = workspaceData(storage, workspacePath).modules.first { it.name == "consumer" }
        val moduleDeps = consumer.dependencies.filterIsInstance<DependencyData.Module>().map { it.name }.toSet()
        val libraryDeps = consumer.dependencies.filterIsInstance<DependencyData.Library>().map { it.name }.toSet()

        assertEquals(setOf("lib"), moduleDeps, "consumer's library dep on com.example:lib:1.0 should become a module dep on lib")
        assertEquals(setOf("Maven: com.other:other:2.0"), libraryDeps, "unmatched library dep must be left untouched")
    }
}
