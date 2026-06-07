// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.workspaceModel.ide.impl.IdeVirtualFileUrlManagerImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DependencyFlatteningTest {
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
