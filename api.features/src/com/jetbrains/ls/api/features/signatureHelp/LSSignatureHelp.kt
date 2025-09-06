// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.signatureHelp

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.SignatureHelp
import com.jetbrains.lsp.protocol.SignatureHelpParams

object LSSignatureHelp {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getSignatureHelp(params: SignatureHelpParams): SignatureHelp? {
        val handlers = entriesFor<LSSignatureHelpProvider>(params.textDocument)
        return when (handlers.size) {
            0 -> null
            1 -> handlers.first().getSignatureHelp(params)
            else -> {
                error("Multiple signature help providers found for ${params.textDocument}: ${handlers}")
            }
        }
    }
}