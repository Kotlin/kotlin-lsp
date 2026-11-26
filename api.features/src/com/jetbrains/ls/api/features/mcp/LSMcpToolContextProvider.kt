// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.mcp

import com.intellij.openapi.project.Project
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext

interface LSMcpToolContextProvider : LSConfigurationEntry {
    /** Names the MCP tools that this context supports. */
    val supportedTools: Set<String>

    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun <T> withToolContext(block: suspend (project: Project) -> T): T
}
