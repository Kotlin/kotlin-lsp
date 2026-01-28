// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.implementation.LspHandlerContext
import kotlinx.serialization.json.JsonElement

fun interface LSCommandExecutor {
    context(server: LSServer, handlerContext: LspHandlerContext)
    suspend fun execute(arguments: List<JsonElement>): JsonElement
}
