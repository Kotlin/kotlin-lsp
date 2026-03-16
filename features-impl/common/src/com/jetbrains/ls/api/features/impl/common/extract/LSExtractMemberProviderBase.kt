// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.extract

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.document.LSDocumentCommandExecutor
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Base class for extract refactorings (variable, method, etc.) in LSP.
 * Expected workflow:
 * 1. Fetch available options to extract in the given range with [getChoices].
 * 2. Based on the user selection in step 1, create a context with [getWriteContext].
 * 3. Execute the extraction with [doExtract].
 */
abstract class LSExtractMemberProviderBase<Context> : LSCodeActionProvider, LSCommandDescriptorProvider {
    protected abstract val commandName: String
    protected abstract val descriptorTitle: String
    protected abstract val extractActionKind: CodeActionKind

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = descriptorTitle,
                name = commandName,
                executor = object : LSDocumentCommandExecutor {
                    context(server: LSServer, handlerContext: LspHandlerContext)
                    override suspend fun executeForDocument(
                        documentUri: DocumentUri,
                        otherArgs: List<JsonElement>
                    ): List<TextEdit> {
                        return server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
                            val (file, data) = readAction {
                                val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                                val data = otherArgs.firstOrNull()?.let { LSP.json.decodeFromJsonElement(ExtractData.serializer(), it) }
                                    ?: return@readAction null
                                virtualFile to data
                            } ?: return@withWriteAnalysisContextAndFileSettings emptyList()
                            computeExtractEdits(file, data)
                        }
                    }
                },
            ),
        )

    override val providesOnlyKinds: Set<CodeActionKind>
        get() = setOf(extractActionKind)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val result = server.withAnalysisContext {
            readAction {
                val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                val choicesResult = getChoices(virtualFile, params.range.toTextRange(document)) ?: return@readAction null
                val selectionRange = choicesResult.selection.toLspRange(document)
                choicesResult.choices to selectionRange
            }
        } ?: return@flow

        for (choice in result.first) {
            emit(
                CodeAction(
                    title = choice,
                    kind = extractActionKind,
                    command = Command(
                        title = choice,
                        command = commandName,
                        arguments = listOf<JsonElement>(
                            LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                            LSP.json.encodeToJsonElement<ExtractData>(
                                ExtractData.serializer(),
                                ExtractData(result.second, choice)
                            ),
                        ),
                    )
                )
            )
        }
    }

    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun computeExtractEdits(file: VirtualFile, data: ExtractData): List<TextEdit> {
        val (writeContext, oldDocumentText) = readAction {
            val document = file.findDocument() ?: return@readAction null
            val selection = data.selection.toTextRange(document)
            getWriteContext(file, selection, data.choice) to document.text
        } ?: return emptyList()

        server.withWritableFile(file.uri) {
            val postProcessReformatting = PostprocessReformattingAspect.getInstance(project)
            if (postProcessReformatting == null) {
                LOG.error("Wasn't able to initialize PostProcessReformattingAspect")
            }
            doExtract(writeContext)
        }

        return readAction {
            val document = file.findDocument() ?: return@readAction emptyList()
            computeTextEdits(oldDocumentText, document.text)
        }
    }

    /**
     * Calculates the available extraction types in the given [selectedRange] and the adjusted selection.
     * @return null if it is impossible to extract in the given position, [ChoicesResult] with displayable choices and adjusted selection otherwise
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getChoices(file: VirtualFile, selectedRange: TextRange): ChoicesResult?

    /**
     * Creates a context for the extraction based on the given [choice] and pre-computed [selection].
     * @return context for the extraction. It is expected that context can always be retrieved since the [choice] was shown to the user.
     * @param selection the adjusted selection as computed by [getChoices], not the raw client range
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getWriteContext(file: VirtualFile, selection: TextRange, choice: String): Context

    /**
     * Executes the extraction on the given [context].
     * After this method is called, the member is extracted and computation of the edits is performed.
     */
    context(server: LSServer, analysisContext: LSAnalysisContext)
    protected abstract suspend fun doExtract(context: Context)

    protected data class ChoicesResult(val choices: List<String>, val selection: TextRange)

    @Serializable
    data class ExtractData(val selection: Range, val choice: String)
    companion object{
        private val LOG = Logger.getInstance(LSExtractMemberProviderBase::class.java)
    }
}
