// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.highwatermark

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.nio.file.Path

object LSHighWatermarkCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor(LspServerBundle.message("command.set.high.watermark.file"), SET_HIGH_WATERMARK_FILE_COMMAND) { arguments ->
            val path = decodeSingleArgument<String>(arguments, "a file path")
            contextOf<LSServer>().setHighWatermarkFile(Path.of(path).toAbsolutePath().normalize())
            JsonNull
        },
        LSCommandDescriptor(LspServerBundle.message("command.wait.for.high.watermark"), WAIT_FOR_HIGH_WATERMARK_COMMAND) { arguments ->
            val timestamp = decodeSingleArgument<Long>(arguments, "a timestamp number")
            contextOf<LSServer>().waitForHighWatermark(timestamp)
            JsonNull
        },
    )

    context(_: LspHandlerContext)
    private inline fun <reified T> decodeSingleArgument(arguments: List<JsonElement>, expected: String): T {
        if (arguments.size != 1) {
            throwLspError(
                ExecuteCommand,
                "Expected $expected as the only argument, got: ${arguments.size} arguments",
                Unit,
                ErrorCodes.InvalidParams,
                null,
            )
        }
        return try {
            LSP.json.decodeFromJsonElement<T>(arguments.single())
        }
        catch (e: Exception) {
            throwLspError(ExecuteCommand, "Expected $expected: ${e.message}", Unit, ErrorCodes.InvalidParams, e)
        }
    }

    const val SET_HIGH_WATERMARK_FILE_COMMAND: String = "set-highwatermark-file"
    const val WAIT_FOR_HIGH_WATERMARK_COMMAND: String = "wait-for-highwatermark"
}
