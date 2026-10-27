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

    /**
     * The group of the code actions of this provider in a response. By default, a provider of quick fixes only
     * is in [LSCodeActionGroup.QuickFixes], and another provider is in [LSCodeActionGroup.Other].
     */
    val group: LSCodeActionGroup
        get() = if (providesOnlyKinds.all { it.isKindOf(CodeActionKind.QuickFix) }) LSCodeActionGroup.QuickFixes else LSCodeActionGroup.Other

    context(server: LSServer, handlerContext: LspHandlerContext)
    fun getCodeActions(params: CodeActionParams): Flow<CodeAction>
}

/**
 * The groups of code actions in a response, in the order of the entries. The IDE lists the actions of a warning in the
 * same order: its fixes, then its options, such as a suppression, then the intentions.
 */
enum class LSCodeActionGroup {
    /** The quick fixes of the diagnostics. */
    QuickFixes,

    /** The actions that change how the inspection of a diagnostic reports it, such as a suppression. */
    InspectionOptions,

    /** The intentions, the refactorings, and the source actions. */
    Other,
}
