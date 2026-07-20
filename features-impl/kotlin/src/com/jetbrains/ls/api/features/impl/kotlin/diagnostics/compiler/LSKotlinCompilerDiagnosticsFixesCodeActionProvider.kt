// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.kotlin.diagnostics.compiler

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.combinedPresentationNames
import com.jetbrains.ls.api.features.impl.common.modcommands.flattenChoiceActions
import com.jetbrains.ls.api.features.impl.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixService
import org.jetbrains.kotlin.psi.KtFile

private val LOG = logger<LSKotlinCompilerDiagnosticsFixesCodeActionProvider>()

internal object LSKotlinCompilerDiagnosticsFixesCodeActionProvider : LSCodeActionProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val diagnosticData = params.diagnosticData<KotlinCompilerDiagnosticData>().ifEmpty { return@flow }

        val uri = params.textDocument.uri.uri
        server.withWriteAnalysisContextAndFileSettings(uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                val quickFixService = KotlinQuickFixService.getInstance()
                val ktFile = virtualFile.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                val document = virtualFile.findDocument() ?: return@readAction emptyList()
                analyze(ktFile) {
                    val kaDiagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                    if (kaDiagnostics.isEmpty()) return@analyze emptyList()
                    val editor = ImaginaryEditor(project, document).apply {
                        caretModel.primaryCaret.moveToOffset(0)
                    }
                    val result = mutableListOf<CodeAction>()
                    for (data in diagnosticData) {
                        val kaDiagnostic = kaDiagnostics.firstOrNull { data.data.matches(it) } ?: continue
                        with(quickFixService) {
                            result += getQuickFixesAsCodeActions(
                                ktFile,
                                editor,
                                kaDiagnostic,
                                data.diagnostic,
                            )
                        }
                    }
                    result
                }
            }
        }.forEach { emit(it) }
    }

    context(server: LSServer, kaSession: KaSession)
    private fun KotlinQuickFixService.getQuickFixesAsCodeActions(
        file: KtFile,
        editor: Editor,
        kaDiagnostic: KaDiagnosticWithPsi<*>,
        lspDiagnostic: Diagnostic,
    ): List<CodeAction> = with(kaSession) {
        return (getQuickFixesWithCatchingFor(kaDiagnostic) + getLazyQuickFixesWithCatchingFor(kaDiagnostic))
            .mapNotNull { fixes ->
                fixes.getOrHandleException { LOG.warn(it) }
            }
            .mapNotNull { intentionAction ->
                val modCommandAction = intentionAction.asModCommandAction()
                if (modCommandAction == null) {
                    LOG.warn("Cannot convert $intentionAction to ModCommandAction")
                }
                modCommandAction
            }
            .flatMap { modCommandAction ->
                val context = ActionContext.from(editor, file)
                if (server.config.clientSupportsIntellijExtensions) {
                    // A ModChooseAction is represented natively (a menu shown at execution time), so a single
                    // code action is emitted and the choice tree does not need to be flattened up front.
                    nativeCodeActions(modCommandAction, context, lspDiagnostic)
                } else {
                    // Generic LSP clients cannot show a choice menu, so expand top-level ModChooseActions into
                    // separate flat code actions.
                    flattenedCodeActions(modCommandAction, context, lspDiagnostic)
                }
            }
            .toList()
    }

    context(server: LSServer)
    private fun nativeCodeActions(
        modCommandAction: ModCommandAction,
        context: ActionContext,
        lspDiagnostic: Diagnostic,
    ): List<CodeAction> {
        val presentation = runCatching {
            modCommandAction.getPresentation(context)
        }.getOrHandleException {
            LOG.warn("Failed to get presentation from mod command action $modCommandAction", it)
        } ?: return emptyList()

        val modCommand = runCatching {
            modCommandAction.perform(context)
        }.getOrHandleException {
            LOG.warn("Failed to perform mod command action $modCommandAction", it)
        } ?: return emptyList()

        val modCommandData = ModCommandData.from(modCommand, context, server) ?: return emptyList()
        return listOf(codeAction(presentation.name, modCommandData, lspDiagnostic))
    }

    context(server: LSServer)
    private fun flattenedCodeActions(
        modCommandAction: ModCommandAction,
        context: ActionContext,
        lspDiagnostic: Diagnostic,
    ): List<CodeAction> =
        modCommandAction.flattenChoiceActions(context).mapNotNull { chain ->
            val modCommandData = ModCommandData.from(chain.leaf.command, context, server) ?: return@mapNotNull null
            codeAction(chain.combinedPresentationNames(), modCommandData, lspDiagnostic)
        }

    private fun codeAction(title: String, modCommandData: ModCommandData, lspDiagnostic: Diagnostic): CodeAction =
        CodeAction(
            title,
            CodeActionKind.QuickFix,
            diagnostics = listOf(lspDiagnostic),
            command = Command(
                LSApplyFixCommandDescriptorProvider.commandDescriptor.title,
                LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
                arguments = listOf(
                    LSP.json.encodeToJsonElement(modCommandData),
                ),
            ),
        )
}
