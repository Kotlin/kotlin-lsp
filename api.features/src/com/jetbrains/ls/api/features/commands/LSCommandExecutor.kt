package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.lsp.implementation.LspHandlerContext
import kotlinx.serialization.json.JsonElement

fun interface LSCommandExecutor {
    context(LspHandlerContext, LSServer)
    suspend fun execute(arguments: List<JsonElement>): JsonElement
}
