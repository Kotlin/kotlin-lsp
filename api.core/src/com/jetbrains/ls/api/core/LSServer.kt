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
        action: suspend context(LSAnalysisContext) CoroutineScope.() -> R,
    ): R

    suspend fun <R> withWriteAnalysisContext(
        action: suspend context(LSAnalysisContext, CoroutineScope) () -> R,
    ): R

    suspend fun <R> withWritableFile(
        useSiteFileUri: URI,
        action: suspend CoroutineScope.() -> R,
    ): R

    val documents: LSDocuments

    val workspaceStructure: LSWorkspaceStructure
}

context(server: LSServer)
inline val documents: LSDocuments get() = server.documents

context(server: LSServer)
inline val workspaceStructure: LSWorkspaceStructure get() = server.workspaceStructure

context(server: LSServer)
suspend fun <R> withAnalysisContext(action: suspend context(LSAnalysisContext) CoroutineScope.() -> R): R =
    server.withAnalysisContext(action)

context(server: LSServer)
suspend fun <R> withWriteAnalysisContext(action: suspend context(LSAnalysisContext) CoroutineScope.() -> R): R =
    server.withWriteAnalysisContext(action)

context(server: LSServer)
suspend fun <R> withWritableFile(useSiteFileUri: URI, action: suspend CoroutineScope.() -> R): R =
    server.withWritableFile(useSiteFileUri, action)

interface LSAnalysisContext {
    val project: Project
}

context(context: LSAnalysisContext)
val project: Project get() = context.project

interface LSServerStarter {
    suspend fun start(action: suspend context(LSServerContext) CoroutineScope.() -> Unit)
}

interface LSServerContext {
    suspend fun withServer(action: suspend context(LSServer) CoroutineScope.() -> Unit)
}

context(context: LSServerContext)
suspend fun withServer(action: suspend context(LSServer) CoroutineScope.() -> Unit): Unit =
    context.withServer(action)
