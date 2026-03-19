// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.extract

import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.util.startOffset
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
import com.jetbrains.ls.api.features.commands.LSCommandExecutor
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.ShowDocument
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
                executor = object : LSCommandExecutor {
                    context(server: LSServer, handlerContext: LspHandlerContext)
                    override suspend fun execute(arguments: List<JsonElement>): JsonElement {
                        require(arguments.size == 2) { "Expected 2 arguments, got: ${arguments.size}" }
                        val documentUri = LSP.json.decodeFromJsonElement<DocumentUri>(arguments.first())
                        val payload = LSP.json.decodeFromJsonElement<Payload>(arguments.last())

                        when (payload) {
                            is Payload.Error -> {
                                lspClient.notify(
                                    ShowMessageNotificationType,
                                    ShowMessageParams(
                                        MessageType.Error,
                                        payload.message,
                                    )
                                )
                            }

                            is Payload.Data -> {
                                val result = server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
                                    val (file, data) = readAction {
                                        val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                                        virtualFile to payload
                                    } ?: return@withWriteAnalysisContextAndFileSettings null
                                    computeExtractResult(file, data)
                                }

                                if (result == null || result.edits.isEmpty()) return JsonPrimitive(true)

                                lspClient.request(
                                    ApplyEditRequests.ApplyEdit,
                                    ApplyWorkspaceEditParams(
                                        label = null,
                                        edit = WorkspaceEdit(
                                            changes = mapOf(documentUri to result.edits)
                                        ),
                                    ),
                                )

                                if (result.navigationRange != null) {
                                    lspClient.request(
                                        requestType = ShowDocument,
                                        params = ShowDocumentParams(
                                            uri = documentUri.uri,
                                            external = false,
                                            takeFocus = true,
                                            selection = result.navigationRange,
                                        ),
                                    )
                                }
                            }
                        }

                        return JsonPrimitive(true)
                    }
                },
            ),
        )

    override val providesOnlyKinds: Set<CodeActionKind>
        get() = setOf(extractActionKind)

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val choicesResult = server.withAnalysisContext {
            readAction {
                val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                getChoices(virtualFile, params.range.toTextRange(document))
            }
        } ?: return@flow

        when (choicesResult) {
            is ChoicesResult.Choices -> {
                val selectionRange = server.withAnalysisContext {
                    readAction {
                        val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                        val document = virtualFile.findDocument() ?: return@readAction null
                        choicesResult.selection.toLspRange(document)
                    }
                } ?: return@flow
                for (choice in choicesResult.choices) {
                    emit(
                        CodeAction(
                            title = choice,
                            kind = extractActionKind,
                            command = Command(
                                title = choice,
                                command = commandName,
                                arguments = listOf(
                                    LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                                    LSP.json.encodeToJsonElement<Payload>(Payload.Data(selectionRange, choice)),
                                ),
                            )
                        )
                    )
                }
            }

            is ChoicesResult.Error -> {
                emit(
                    CodeAction(
                        title = choicesResult.defaultTitle,
                        kind = extractActionKind,
                        command = Command(
                            title = choicesResult.defaultTitle,
                            command = commandName,
                            arguments = listOf(
                                LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                                LSP.json.encodeToJsonElement<Payload>(Payload.Error(choicesResult.errorMessage)),
                            ),
                        )
                    )
                )
            }
        }
    }

    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun computeExtractResult(file: VirtualFile, data: Payload.Data): ExtractResult {
        val (writeContext, oldDocumentText) = readAction {
            val document = file.findDocument() ?: return@readAction null
            val selection = data.selection.toTextRange(document)
            getWriteContext(file, selection, data.choice) to document.text
        } ?: return ExtractResult(emptyList(), null)

        val navigationRange = server.withWritableFile(file.uri) {
            val postProcessReformatting = PostprocessReformattingAspect.getInstance(project)
            if (postProcessReformatting == null) {
                LOG.error("Wasn't able to initialize PostProcessReformattingAspect")
            }
            val reference = doExtract(writeContext) ?: return@withWritableFile null
            TextRange(reference.startOffset, reference.startOffset)
        }

        return readAction {
            val document = file.findDocument() ?: return@readAction ExtractResult.EMPTY
            ExtractResult(
                computeTextEdits(oldDocumentText, document.text),
                navigationRange?.toLspRange(document)
            )
        }
    }

    /**
     * Calculates the available extraction types in the given [selectedRange] and the adjusted selection.
     * @return null if it is impossible to extract in the given position,
     * [com.jetbrains.ls.api.features.impl.common.extract.LSExtractMemberProviderBase.ChoicesResult.Choices] when extraction is possible,
     * or [com.jetbrains.ls.api.features.impl.common.extract.LSExtractMemberProviderBase.ChoicesResult.Error] if the extraction is impossible and
     * the error should be displayed to the user.
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
     *
     * @return the element to which the caret position should be navigated
     */
    context(server: LSServer, analysisContext: LSAnalysisContext)
    protected abstract suspend fun doExtract(context: Context): PsiElement?

    private data class ExtractResult(val edits: List<TextEdit>, val navigationRange: Range?) {
        companion object {
            val EMPTY = ExtractResult(emptyList(), null)
        }
    }

    protected sealed interface ChoicesResult {
        data class Choices(val choices: List<String>, val selection: TextRange) : ChoicesResult
        data class Error(val defaultTitle: String, val errorMessage: String) : ChoicesResult
    }

    @Serializable
    private sealed interface Payload {
        @Serializable
        data class Data(val selection: Range, val choice: String) : Payload

        @Serializable
        data class Error(val message: String) : Payload
    }

    companion object {
        private val LOG = Logger.getInstance(LSExtractMemberProviderBase::class.java)
    }
}
