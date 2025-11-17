// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.diagnostics.inspections

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement

class LSInspectionFixesCodeActionProvider(
    override val supportedLanguages: Set<LSLanguage>,
) : LSCodeActionProvider {

    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(_: LSServer, _: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val diagnosticData = params.diagnosticData<InspectionDiagnosticData>().ifEmpty { return@flow }
        diagnosticData.flatMap { data ->
            data.data.fixes.map { quickFix ->
                CodeAction(
                    title = quickFix.name,
                    kind = CodeActionKind.QuickFix,
                    diagnostics = listOf(data.diagnostic),
                    command = Command(
                        title = LSInspectionQuickFixCommandDescriptorProvider.applyQuickFixCommandDescriptor.title,
                        command = LSInspectionQuickFixCommandDescriptorProvider.applyQuickFixCommandDescriptor.name,
                        arguments = listOf(
                            LSP.json.encodeToJsonElement(quickFix.modCommandData),
                        ),
                    ),
                )
            }
        }.forEach { codeAction -> emit(codeAction) }
    }
}