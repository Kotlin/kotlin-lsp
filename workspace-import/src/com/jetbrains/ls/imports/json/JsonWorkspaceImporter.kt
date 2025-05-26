// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.notExists

object JsonWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit
    ): MutableEntityStorage {
        try {
            val content = (projectDirectory / "workspace.json").toFile().readText()
            val workspaceData = Json.decodeFromString<WorkspaceData>(content)
            workspaceData.modules.forEach { module ->
                module.dependencies
                    .filterIsInstance<DependencyData.Library>()
                    .filter { it.name != "JDK" }
                    .filter { dependency -> workspaceData.libraries.none { it.name == dependency.name } }
                    .forEach { onUnresolvedDependency(it.name.removePrefix("Gradle: ")) }
            }
            workspaceData.libraries.forEach { library ->
                if (library.roots.any { toAbsolutePath(it.path, projectDirectory).notExists()}) {
                    onUnresolvedDependency(library.name.removePrefix("Gradle: "))
                }
            }
            return workspaceModel(
                workspaceData,
                projectDirectory,
                WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
                virtualFileUrlManager
            )
        } catch (e: SerializationException) {
            throw WorkspaceImportException(
                "Error parsing workspace.json",
                "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}"
            )
        } catch (e: Exception) {
            throw e
        }
    }

    override fun isApplicableDirectory(projectDirectory: Path): Boolean =
        (projectDirectory / "workspace.json").exists()

}