// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.formatting

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DocumentFormattingParams
import com.jetbrains.lsp.protocol.DocumentRangeFormattingParams
import com.jetbrains.lsp.protocol.TextEdit

interface LSFormattingProvider : LSLanguageSpecificConfigurationEntry {
    context(_: LSServer, _: LspHandlerContext)
    suspend fun getFormatting(params: DocumentFormattingParams): List<TextEdit>?

    context(_: LSServer, _: LspHandlerContext)
    suspend fun getFormattingRanged(params: DocumentRangeFormattingParams): List<TextEdit>?
}