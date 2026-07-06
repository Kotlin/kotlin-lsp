// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.codeLens

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams
import kotlinx.coroutines.flow.Flow

/**
 * Provides [CodeLens]es for a document: actionable annotations (a [com.jetbrains.lsp.protocol.Command])
 *
 * Implementations back the LSP `textDocument/codeLens` request. All providers whose
 * [supportedLanguages][com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry.supportedLanguages]
 * match the requested document are queried, and their results are merged by [LSCodeLens].
 */
interface LSCodeLensProvider : LSLanguageSpecificConfigurationEntry {
    /**
     * Emits the lenses for the document identified by [params]. The flow is collected lazily and
     * may be streamed back to the client incrementally when a partial-result token is present.
     *
     * Each emitted [CodeLens] must be self-contained: the server advertises `resolveProvider = false`,
     * so there is no `codeLens/resolve` round-trip and every lens has to carry its fully populated
     * [command][CodeLens.command] up front.
     */
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun getCodeLenses(params: CodeLensParams): Flow<CodeLens>
}
