// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core

import com.intellij.openapi.project.Project
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.CoroutineScope

interface LSServer { // workspace?
    /**
     * @param useSiteFileUri we may have multiple projects in the workspace, [URI] is used to find a correct one
     */
    suspend fun <R> withAnalysisContext(
        action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
    ): R

    suspend fun <R> withWritableFile(
        useSiteFileUri: URI,
        action: suspend context(CoroutineScope) () -> R,
    ): R

    val documents: LSDocuments

    val workspaceStructure: LSWorkspaceStructure
}

interface LSAnalysisContext {
    val project: Project
}

interface LSServerStarter {
    suspend fun start(action: suspend context(LSServerContext, CoroutineScope) () -> Unit)
}

interface LSServerContext {
    suspend fun withServer(action: suspend context(LSServer, CoroutineScope) () -> Unit)
}
