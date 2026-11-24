// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.compaction

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.ErrorCodes
import kotlinx.serialization.json.JsonNull

/**
 * The command a client runs before it copies the index database, such as an image build after a warmup: once the
 * command returns, the database has its smallest size and no compaction left to run (see [LSServer.compactIndex]).
 *
 * `intellij/ready-for-test` does not wait for a compaction, because the index already answers while one runs.
 */
object LSIndexCompactionCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> = listOf(
        LSCommandDescriptor(LspServerBundle.message("command.compact.index"), COMPACT_INDEX_COMMAND) { arguments ->
            if (arguments.isNotEmpty()) {
                throwLspError(ExecuteCommand, "Expected no arguments, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
            }
            contextOf<LSServer>().compactIndex()
            JsonNull
        },
    )

    const val COMPACT_INDEX_COMMAND: String = "compact-index"
}
