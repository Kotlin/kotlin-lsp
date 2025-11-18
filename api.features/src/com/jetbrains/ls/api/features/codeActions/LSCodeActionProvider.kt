// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.codeActions

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import kotlinx.coroutines.flow.Flow

/**
 * Provides code actions (known as quick-fixes in IntelliJ).
 */
interface LSCodeActionProvider : LSLanguageSpecificConfigurationEntry {
    /**
     * Only code actions of these kinds will be provided. Cannot be empty.
     *
     * [getCodeActions] cannot provide kinds not listed in [providesOnlyKinds]. Otherwise, it an exception will be thrown.
     */
    val providesOnlyKinds: Set<CodeActionKind>

    context(_: LSServer, _: LspHandlerContext)
    fun getCodeActions(params: CodeActionParams): Flow<CodeAction>
}
