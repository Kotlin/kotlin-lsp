// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder
import com.intellij.openapi.projectRoots.impl.JavaHomeFinder.getFinder
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.utils.applyChangesWithDeduplication
import java.nio.file.Path

interface WorkspaceImporter {
    suspend fun importWorkspace(
        project: Project,
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit
    ): EntityStorage?
}

suspend fun WorkspaceImporter.importWorkspaceToStorage(
    project: Project,
    storage: MutableEntityStorage,
    projectDirectory: Path,
    virtualFileUrlManager: VirtualFileUrlManager,
    onUnresolvedDependency: (String) -> Unit
): Boolean {
    val diff = importWorkspace(project, projectDirectory, virtualFileUrlManager, onUnresolvedDependency) ?: return false
    applyChangesWithDeduplication(storage, diff)
    return true
}

fun findJdks(projectPath: Path): Set<JavaHomeFinder.JdkEntry> {
    val knownJdks = getFinder(projectPath.getEelDescriptor())
        .checkConfiguredJdks(false)
        .checkEmbeddedJava(false)
        .findExistingJdkEntries()
    if (knownJdks.isEmpty()) {
        throw WorkspaceImportException(
            "Unable to find JDK for Gradle execution. No JDK's found on the machine!",
            "There are no JDKs on the machine. Unable to run Gradle."
        )
    }
    return knownJdks
}

class WorkspaceImportException(
    displayMessage: String,
    val logMessage: String?,
    cause: Throwable? = null
) : Exception(displayMessage, cause)