// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

class LSCommonInspectionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
) : LSCodeActionProvider {

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        params.diagnosticData<SimpleDiagnosticData>()
            .filter { it.data.diagnosticSource == LSCommonInspectionDiagnosticProvider.diagnosticSource }
            .flatMap { data ->
                data.data.fixes.map { quickFix ->
                    CodeAction(
                        title = quickFix.name,
                        kind = CodeActionKind.QuickFix,
                        diagnostics = listOf(data.diagnostic),
                        command = Command(
                            title = LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
                            command = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
                            arguments = listOf(
                                LSP.json.encodeToJsonElement(quickFix.modCommandData),
                            ),
                        ),
                    )
                }
            }
            .forEach { codeAction -> emit(codeAction) }
    }
}
