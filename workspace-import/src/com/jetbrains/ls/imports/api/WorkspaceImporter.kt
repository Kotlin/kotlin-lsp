// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.api

import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import java.nio.file.Path

interface WorkspaceImporter {
    suspend fun tryImportWorkspace(projectDirectory: Path, virtualFileUrlManager: VirtualFileUrlManager, onUnresolvedDependency: (String) -> Unit): MutableEntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null
        return importWorkspace(projectDirectory, virtualFileUrlManager, onUnresolvedDependency)
    }

    suspend fun importWorkspace(projectDirectory: Path, virtualFileUrlManager: VirtualFileUrlManager, onUnresolvedDependency: (String) -> Unit): MutableEntityStorage
    fun isApplicableDirectory(projectDirectory: Path): Boolean
}

class WorkspaceImportException(displayMessage: String, val logMessage: String?): Exception(displayMessage)