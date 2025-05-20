package com.jetbrains.ls.imports.api

import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import java.nio.file.Path

interface WorkspaceImporter {
    suspend fun tryImportWorkspace(projectDirectory: Path, virtualFileUrlManager: VirtualFileUrlManager): MutableEntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null
        return importWorkspace(projectDirectory, virtualFileUrlManager)
    }

    suspend fun importWorkspace(projectDirectory: Path, virtualFileUrlManager: VirtualFileUrlManager): MutableEntityStorage
    fun isApplicableDirectory(projectDirectory: Path): Boolean
}

class WorkspaceImportException(displayMessage: String, val logMessage: String?): Exception(displayMessage)