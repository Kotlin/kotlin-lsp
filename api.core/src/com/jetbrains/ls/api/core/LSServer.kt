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

    /**
     * Runs the given action inside an analysis context that is bound to the current project and
     * the provided document.
     *
     * This overload is thought to be used for actions that require a specific document to be
     * available during analysis.
     * Moreover, it allows specifying the document URI that should be analyzed making it available inside
     * the action's context. This is mainly used in `isolatedDocumentsMode`, where thanks to
     * [requestedDocumentUri] the analysis context is referred to the provided document.
     *
     * In general, prefer this overload for LSP handlers that are inherently tied to a specific document,
     * or if you are in `isolatedDocumentsMode`.
     *
     * @param requestedDocumentUri The URI of the document that should be analyzed.
     * @param action The action to run inside the project's analysis context.
     * @return The result of the action.
     */
    suspend fun <R> withAnalysisContext(
        requestedDocumentUri: URI,
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

context(server: LSServer)
inline val workspaceStructure: LSWorkspaceStructure get() = server.workspaceStructure

context(server: LSServer)
suspend fun <R> withAnalysisContext(action: suspend context(LSAnalysisContext) CoroutineScope.() -> R): R =
    server.withAnalysisContext(action)

context(server: LSServer)
suspend fun <R> withAnalysisContext(requestedDocumentUri: URI, action: suspend context(LSAnalysisContext) CoroutineScope.() -> R): R =
    server.withAnalysisContext(requestedDocumentUri, action)

context(server: LSServer)
suspend fun <R> withWriteAnalysisContext(action: suspend context(LSAnalysisContext) CoroutineScope.() -> R): R =
    server.withWriteAnalysisContext(action)

context(server: LSServer)
suspend fun <R> withWritableFile(useSiteFileUri: URI, action: suspend CoroutineScope.() -> R): R =
    server.withWritableFile(useSiteFileUri, action)

context(server: LSServer)
suspend fun withRenamesEnabled( action: suspend CoroutineScope.() -> Unit): Map<URI, URI> =
    server.withRenamesEnabled(action)

interface DapContext {
    val project: Project
}

context(context: DapContext)
val project: Project get() = context.project

interface LSAnalysisContext {
    val project: Project
}

context(context: LSAnalysisContext)
val project: Project get() = context.project

interface LSServerStarter {
    suspend fun withServer(action: suspend context(LSServer) CoroutineScope.() -> Unit)
}

context(context: LSServerStarter)
suspend fun withServer(action: suspend context(LSServer) CoroutineScope.() -> Unit): Unit =
    context.withServer(action)
