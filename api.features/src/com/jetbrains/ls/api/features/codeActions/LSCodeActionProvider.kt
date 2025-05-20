package com.jetbrains.ls.api.features.codeActions

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow

interface LSCodeActionProvider : LSLanguageSpecificConfigurationEntry {
    context(LSServer)
    fun getCodeActions(params: CodeActionParams): Flow<CodeAction>
}
