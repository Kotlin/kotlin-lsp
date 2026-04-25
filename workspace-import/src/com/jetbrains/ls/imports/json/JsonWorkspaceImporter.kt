// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.json

import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.impl.url.toVirtualFileUrl
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImportProgressReporter
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.utils.fixMissingProjectSdk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

object JsonWorkspaceImporter : WorkspaceImporter {

    override fun canImportWorkspace(projectDirectory: Path): Boolean =
        (projectDirectory / "workspace.json").exists()

    override suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter
    ): EntityStorage? {
        if (!canImportWorkspace(projectDirectory)) return null
        val jsonPath = projectDirectory / "workspace.json"
        return importWorkspaceJson(
            jsonPath, projectDirectory, defaultSdkPath, virtualFileUrlManager, progress
        )
    }

    fun importWorkspaceJson(
        file: Path,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        progress: WorkspaceImportProgressReporter
    ): EntityStorage {
        val workspaceJson: WorkspaceData = try {
            file.inputStream().use { stream ->
                @OptIn(ExperimentalSerializationApi::class)
                Json.decodeFromStream(stream)
            }
        } catch (e: SerializationException) {
            throw WorkspaceImportException(
                "Error parsing workspace.json",
                "Error parsing workspace.json:\n ${e.message ?: e.stackTraceToString()}",
                e
            )
        }
        return MutableEntityStorage.create().apply {
            importWorkspaceData(
                postProcessWorkspaceData(
                    workspaceJson,
                    projectDirectory,
                    progress
                ),
                projectDirectory,
                WorkspaceEntitySource(projectDirectory.toVirtualFileUrl(virtualFileUrlManager)),
                virtualFileUrlManager
            )
            fixMissingProjectSdk(defaultSdkPath, virtualFileUrlManager)
        }
    }

    fun postProcessWorkspaceData(
        workspaceData: WorkspaceData,
        projectDirectory: Path,
        progress: WorkspaceImportProgressReporter,
    ): WorkspaceData {
        val reportUnresolvedName: (String) -> Unit = { name ->
            progress.onUnresolvedDependency(name.removeSuffix("Gradle: ").removeSuffix("Maven: "))
        }
        workspaceData.modules.forEach { module ->
            module.dependencies
                .filterIsInstance<DependencyData.Library>()
                .filter { it.name != "JDK" }
                .filter { dependency -> workspaceData.libraries.none { it.name == dependency.name } }
                .forEach { reportUnresolvedName(it.name) }
        }
        workspaceData.libraries.forEach { library ->
            if (library.roots.any { toAbsolutePath(it.path, projectDirectory).notExists() }) {
                reportUnresolvedName(library.name)
            }
        }
        return workspaceData
    }
}