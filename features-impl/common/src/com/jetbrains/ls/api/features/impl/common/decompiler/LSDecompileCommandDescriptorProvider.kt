// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.decompiler

import com.jetbrains.ls.api.core.util.scheme
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.decompiler.DecompilerResponse
import com.jetbrains.ls.api.features.decompiler.LSDecompiler
import com.jetbrains.lsp.implementation.throwLspError
import com.jetbrains.lsp.protocol.Commands.ExecuteCommand
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The command predates `workspace/textDocumentContent` and stays for the VSCode client, which needs the `language` field. */
object LSDecompileCommandDescriptorProvider : LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor> get() = listOf(commandDescriptor)

    private val commandDescriptor = LSCommandDescriptor(
        title = "Decompile",
        name = "decompile",
        executor = { arguments ->
            if (arguments.size != 1) {
                throwLspError(ExecuteCommand, "Expected 1 argument, got: ${arguments.size}", Unit, ErrorCodes.InvalidParams, null)
            }
            val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments.first())
            val scheme = documentUri.uri.scheme
            if (scheme !in LSDecompiler.SCHEMES) {
                throwLspError(ExecuteCommand, "Unexpected URI scheme to decompile: $scheme", Unit, ErrorCodes.InvalidParams, null)
            }
            val response: DecompilerResponse? = LSDecompiler.decompile(documentUri)
            response?.let { LSP.json.encodeToJsonElement(it) } ?: JsonPrimitive(null as String?)
        },
    )
}
