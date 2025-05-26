// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.commands

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.ExecuteCommandParams
import kotlinx.serialization.json.JsonElement

object LSCommand {
    context(LSServer, LspHandlerContext, LSConfiguration)
    suspend fun executeCommand(params: ExecuteCommandParams): JsonElement {
        val descriptor = commandDescriptorByCommandName(params.command)
            ?: throwLspError(ExecuteCommand, "Unknown command: ${params.command}", Unit, ErrorCodes.InvalidRequest, null)
        return descriptor.executor.execute(params.arguments ?: emptyList())
    }
}