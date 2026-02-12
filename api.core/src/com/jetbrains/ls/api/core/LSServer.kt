// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core

import com.intellij.openapi.project.Project
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.CoroutineScope

interface LSServer { // workspace?

    /**
     * Runs the given action inside an analysis context that is bound to the current project.
     *
     * This is the default way for getting analysis services in LSP handlers.
     *
     * @param action The action to run inside the project's analysis context.
     * @return The result of the action.
     */
    suspend fun <R> withAnalysisContext(
        action: suspend context(LSAnalysisContext) CoroutineScope.() -> R,
    ): R

    suspend fun <R> withWriteAnalysisContext(
        action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
    ): R

    suspend fun <R> withWritableFile(
        useSiteFileUri: URI,
        action: suspend CoroutineScope.() -> R,
    ): R

    suspend fun withRenamesEnabled(action: suspend CoroutineScope.() -> Unit): Map<URI, URI>

    /**
     * Runs the given action inside a debugger context that is bound to the current project.
     *
     * @param action The action to run inside the project's debugger context.
     * @return The result of the action.
     */
    suspend fun <R> withDebugContext(
        action: suspend context(DapContext) CoroutineScope.() -> R,
    ): R

    val documents: LSDocuments

    val workspaceStructure: LSWorkspaceStructure
}

interface DapContext {
    val project: Project
}

context(dapContext: DapContext)
val project: Project get() = dapContext.project

interface LSAnalysisContext {
    val project: Project
}

context(analysisContext: LSAnalysisContext)
val project: Project get() = analysisContext.project

interface LSServerStarter {
    suspend fun withServer(action: suspend context(LSServer) CoroutineScope.() -> Unit)
}
