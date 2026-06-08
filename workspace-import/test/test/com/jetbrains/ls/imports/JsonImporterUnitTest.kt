// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports

import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import com.jetbrains.ls.imports.json.DependencyData
import com.jetbrains.ls.imports.json.DependencyDataScope
import com.jetbrains.ls.imports.json.LibraryData
import com.jetbrains.ls.imports.json.LibraryRootData
import com.jetbrains.ls.imports.json.ModuleData
import com.jetbrains.ls.imports.json.SdkData
import com.jetbrains.ls.imports.json.WorkspaceData
import com.jetbrains.ls.imports.json.flattenExportedDependencies
import com.jetbrains.ls.imports.json.importWorkspaceData
import com.jetbrains.ls.imports.json.workspaceData
import org.junit.jupiter.api.Assertions.assertEquals
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
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true))

        val sdk = storage.entities<SdkEntity>().single()
        assertTrue(sdk.roots.isEmpty(), "Placeholder JDK home must not be resolved to SDK roots")
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
        storage.importWorkspaceData(data, workspacePath, object : EntitySource {}, IdeVirtualFileUrlManagerImpl(true))

        flattenExportedDependencies(storage)

        val a = workspaceData(storage, workspacePath).modules.first { it.name == "A" }
        val moduleDeps = a.dependencies.filterIsInstance<DependencyData.Module>().map { it.name }.toSet()
        val libraryDeps = a.dependencies.filterIsInstance<DependencyData.Library>().map { it.name }.toSet()

        assertEquals(setOf("B", "C"), moduleDeps, "A should keep B and gain transitively-exported C")
        assertEquals(setOf("L", "M"), libraryDeps, "A should gain libraries reachable via the exported chain")
    }
}
