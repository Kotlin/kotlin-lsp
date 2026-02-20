// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.api

import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import java.nio.file.Path

interface WorkspaceImporter {
    suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): EntityStorage?
}

interface EmptyWorkspaceImporter : WorkspaceImporter {
    fun createEmptyWorkspace(
        defaultSdkPath: Path?,
        virtualFileUrlManager: VirtualFileUrlManager,
    ): EntityStorage?
}

class WorkspaceImportException(
    displayMessage: String,
    val logMessage: String?,
    cause: Throwable? = null
) : Exception(displayMessage, cause)