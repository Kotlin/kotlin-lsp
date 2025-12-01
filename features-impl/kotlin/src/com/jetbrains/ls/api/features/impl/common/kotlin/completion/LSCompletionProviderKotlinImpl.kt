// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.completion.insertCompletion
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.withDanglingFileResolutionMode
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

object LSCompletionProviderKotlinImpl : LSAbstractCompletionProvider() {
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId = LSUniqueConfigurationEntry.UniqueId("KotlinCompletionProvider")
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)
    override val completionCommand: String
        get() = "jetbrains.kotlin.completion.apply"


    context(_: LspHandlerContext, _: LSServer)
    override suspend fun applyCompletion(completionData: CompletionData) {
        val (edits, position) = withAnalysisContext {
            invokeAndWaitIfNeeded {
                runWriteAction {
                    val physicalVirtualFile = completionData.params.textDocument.findVirtualFile() ?: return@runWriteAction null
                    val physicalPsiFile = physicalVirtualFile.findPsiFile(project) ?: return@runWriteAction null
                    val initialText = physicalPsiFile.text

                    withFileForModification(
                        physicalPsiFile,
                    ) { fileForModification ->
                        val document = fileForModification.fileDocument
                        val caretBefore = document.offsetByPosition(completionData.params.position)
                        val completionProcess = createCompletionProcess(project, fileForModification, caretBefore)
                        completionProcess.arranger.registerMatcher(completionData.lookup, CamelHumpMatcher(completionData.itemMatcher.prefix))
                        insertCompletion(project, fileForModification, completionData.lookup, completionProcess.parameters!!)

                        val edits = TextEditsComputer.computeTextEdits(initialText, fileForModification.text)

                        edits to document.positionByOffset(completionProcess.caret.offset)
                    }
                }
            }
        } ?: error("Unable to apply completion")

        lspClient.request(
            ApplyEditRequests.ApplyEdit,
            ApplyWorkspaceEditParams(
                label = null,
                edit = WorkspaceEdit(
                    changes = mapOf(completionData.params.textDocument.uri to edits)
                )
            )
        )

        lspClient.request(
            ShowDocument,
            ShowDocumentParams(
                uri = completionData.params.textDocument.uri.uri,
                external = false,
                takeFocus = true,
                selection = Range(position, position)
            )
        )
    }

    @OptIn(KaImplementationDetail::class)
    context(_: LSAnalysisContext)
    private fun <T> withFileForModification(
        physicalPsiFile: PsiFile,
        action: (fileForModification: KtFile) -> T
    ): T {
        val initialText = physicalPsiFile.text

        val ktPsiFactory = KtPsiFactory(project, eventSystemEnabled = true)
        val fileForModification = ktPsiFactory.createFile(physicalPsiFile.name, initialText)
        fileForModification.originalFile = physicalPsiFile

        return withDanglingFileResolutionMode(fileForModification, KaDanglingFileResolutionMode.IGNORE_SELF) {
            action(fileForModification)
        }
    }
}
