// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.symbols

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolParams
import kotlinx.coroutines.flow.Flow

interface LSDocumentSymbolProvider : LSLanguageSpecificConfigurationEntry {
    context(_: LSServer)
    fun getDocumentSymbols(params: DocumentSymbolParams): Flow<DocumentSymbol>
}