// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
