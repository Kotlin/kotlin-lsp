// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.highwatermark

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.ErrorCodes
import kotlinx.serialization.json.JsonNull

/**
 * The filesystem barrier a client runs before it believes an index-backed answer: once the command returns, every
 * filesystem change the client made before sending it is in the index (see [LSServer.waitForHighWatermark]).
 */
object LSHighWatermarkCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor(LspServerBundle.message("command.wait.for.high.watermark"), WAIT_FOR_HIGH_WATERMARK_COMMAND) { arguments ->
            if (arguments.isNotEmpty()) {
                throwLspError(ExecuteCommand, "Expected no arguments, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
            }
            contextOf<LSServer>().waitForHighWatermark()
            JsonNull
        },
    )

    const val WAIT_FOR_HIGH_WATERMARK_COMMAND: String = "wait-for-highwatermark"
}
