// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.foldingRange

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.FoldingRange
import com.jetbrains.lsp.protocol.FoldingRangeParams
import kotlinx.coroutines.flow.Flow

interface LSFoldingRangeProvider : LSLanguageSpecificConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun provideFoldingRanges(params: FoldingRangeParams): Flow<FoldingRange>
}
