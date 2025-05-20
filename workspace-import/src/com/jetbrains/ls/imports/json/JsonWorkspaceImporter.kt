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

object JsonWorkspaceImporter : WorkspaceImporter {
    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager
    ): MutableEntityStorage {
        try {
            val content = (projectDirectory / "workspace.json").toFile().readText()
            val workspaceData = Json.decodeFromString<WorkspaceData>(content)
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