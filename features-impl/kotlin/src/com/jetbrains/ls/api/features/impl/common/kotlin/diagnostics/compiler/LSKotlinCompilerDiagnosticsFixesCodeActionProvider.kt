// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.diagnostics.compiler

import com.intellij.modcommand.ActionContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.getOrHandleException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.core.withWritableFile
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.impl.common.diagnostics.diagnosticData
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.utils.createEditorWithCaret
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixService
import org.jetbrains.kotlin.psi.KtFile

internal object LSKotlinCompilerDiagnosticsFixesCodeActionProvider : LSCodeActionProvider, LSCommandDescriptorProvider {
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val providesOnlyKinds: Set<CodeActionKind> = setOf(CodeActionKind.QuickFix)

    context(_: LSServer, _: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val diagnosticData = params.diagnosticData<KotlinCompilerDiagnosticData>().ifEmpty { return@flow }

        val uri = params.textDocument.uri.uri
        withWritableFile(uri) {
            withAnalysisContext {
                readAction {
                    val file = params.textDocument.findVirtualFile() ?: return@readAction emptyList()
                    val quickFixService = KotlinQuickFixService.getInstance()
                    val ktFile = file.findPsiFile(project) as? KtFile ?: return@readAction emptyList()
                    val document = file.findDocument() ?: return@readAction emptyList()
                    analyze(ktFile) {
                        val kaDiagnostics = ktFile.collectDiagnostics(filter = KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                        if (kaDiagnostics.isEmpty()) return@analyze emptyList()
                        val editor = createEditorWithCaret(document, caretOffset = 0)
                        val result = mutableListOf<CodeAction>()
                        for (data in diagnosticData) {
                            val kaDiagnostic = kaDiagnostics.firstOrNull { data.data.matches(it) } ?: continue
                            with(quickFixService) {
                                result += getQuickFixesAsCodeActions(
                                    project,
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
            }
        }.forEach { emit(it) }
    }

    context(kaSession: KaSession)
    private fun KotlinQuickFixService.getQuickFixesAsCodeActions(
        project: Project,
        file: KtFile,
        editor: Editor,
        kaDiagnostic: KaDiagnosticWithPsi<*>,
        lspDiagnostic: Diagnostic,
    ): List<CodeAction> = with(kaSession) {
        return (getQuickFixesWithCatchingFor(kaDiagnostic) + getLazyQuickFixesWithCatchingFor(kaDiagnostic))
            .mapNotNull { fixes ->
                fixes.getOrHandleException { LOG.warn(it) }
            }
            .filter { intentionAction ->
                runCatching {
                    // this call may also compute some text inside the intention
                    intentionAction.isAvailable(project, editor, file)
                }.getOrHandleException { LOG.warn(it) } ?: false
            }
            .mapNotNull { intentionAction ->
                val modCommandAction = intentionAction.asModCommandAction()
                if (modCommandAction == null) {
                    LOG.warn("Cannot convert $intentionAction to ModCommandAction")
                    return@mapNotNull null
                }
                val modCommand = modCommandAction.perform(ActionContext.from(editor, file))
                val modCommandData = ModCommandData.from(modCommand) ?: return@mapNotNull null

                CodeAction(
                    intentionAction.text,
                    CodeActionKind.QuickFix,
                    diagnostics = listOf(lspDiagnostic),
                    command = Command(
                        commandDescriptor.title,
                        commandDescriptor.name,
                        arguments = listOf(
                            LSP.json.encodeToJsonElement(modCommandData),
                        ),
                    ),
                )
            }
            .toList()
    }

    private val commandDescriptor = LSCommandDescriptor(
        title = "Kotlin Diagnostic Apply Fix",
        name = "kotlinDiagnostic.applyFix",
        executor = { arguments ->
            val fix = LSP.json.decodeFromJsonElement<ModCommandData>(arguments[0])
            withAnalysisContext {
                executeCommand(fix, lspClient)
            }
            JsonPrimitive(true)
        },
    )


    override val commandDescriptors: List<LSCommandDescriptor> = listOf(commandDescriptor)
}


private val LOG = logger<LSKotlinCompilerDiagnosticsFixesCodeActionProvider>()