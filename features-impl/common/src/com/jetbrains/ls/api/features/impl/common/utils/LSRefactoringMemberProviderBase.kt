// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.utils

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withWriteAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.codeActions.LSCodeActionProvider
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.commands.LSCommandExecutor
import com.jetbrains.ls.api.features.commands.LspCommand
import com.jetbrains.ls.api.features.impl.common.modcommands.CHOICE_SEPARATOR
import com.jetbrains.ls.api.features.impl.common.window.showDocumentIfSupported
import com.jetbrains.ls.kotlinLsp.requests.core.ChooseActionMenuEntry
import com.jetbrains.ls.kotlinLsp.requests.core.ShowChooseActionMenuNotification
import com.jetbrains.ls.kotlinLsp.requests.core.ShowChooseActionMenuParams
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActionKind
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.FileChange
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.WorkspaceEdit
import com.jetbrains.ls.kotlinLsp.requests.core.RENAME_EDITOR_COMMAND
import com.jetbrains.ls.kotlinLsp.requests.core.RunEditorCommandNotification
import com.jetbrains.ls.kotlinLsp.requests.core.RunEditorCommandParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.annotations.Nls

/**
 * Base class for refactorings that are using write context in LSP.
 * Expected workflow:
 * 1. Fetch the available choices to execute in the given range with [getChoices].
 * 2. Based on the user selection in step 1, create a context with [getWriteContext].
 * 3. Execute the action with [executeRefactoring].
 *
 * A client which declares `intellijExtensions` picks a choice from the `intellij/chooseAction` menu. The refactoring runs after the
 * pick. A client without that capability gets one code action for each choice instead.
 *
 * @see LSServer.withWriteAnalysisContext
 */
abstract class LSRefactoringMemberProviderBase<Context> : LSCodeActionProvider, LSCommandDescriptorProvider {
    protected abstract val commandName: String
    protected abstract val descriptorTitle: @LspCommand String
    protected abstract val actionKind: CodeActionKind

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

                            is Payload.Choose -> {
                                lspClient.notify(
                                    ShowChooseActionMenuNotification,
                                    ShowChooseActionMenuParams(
                                        title = descriptorTitle,
                                        entries = payload.choices.map { choice ->
                                            ChooseActionMenuEntry(
                                                name = choice,
                                                command = refactoringCommand(
                                                    documentUri = documentUri,
                                                    title = choice,
                                                    payload = Payload.Data(payload.selection, choice),
                                                ),
                                            )
                                        },
                                    ),
                                )
                            }

                            is Payload.Data -> {
                                val result = server.withWriteAnalysisContextAndFileSettings(documentUri.uri) {
                                    val (file, data) = readAction {
                                        val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                                        virtualFile to payload
                                    } ?: return@withWriteAnalysisContextAndFileSettings null
                                    computeRefactoringResult(file, data)
                                }

                                if (result == null || result.changes.isEmpty()) return JsonPrimitive(true)

                                val applyResult = lspClient.request(
                                    ApplyEditRequests.ApplyEdit,
                                    ApplyWorkspaceEditParams(
                                        label = null,
                                        edit = WorkspaceEdit(
                                            documentChanges = result.changes
                                        ),
                                    ),
                                )

                                if (applyResult.applied && result.navigationRange != null) {
                                    val shown = lspClient.showDocumentIfSupported(
                                        ShowDocumentParams(
                                            uri = documentUri.uri,
                                            external = false,
                                            takeFocus = true,
                                            selection = result.navigationRange,
                                        ),
                                    )
                                    // `editor.action.rename` renames at the caret, which the request above has just placed.
                                    if (shown?.success == true && result.startRename && server.config.clientSupportsIntellijExtensions) {
                                        lspClient.notify(
                                            RunEditorCommandNotification,
                                            RunEditorCommandParams(RENAME_EDITOR_COMMAND),
                                        )
                                    }
                                }
                            }
                        }

                        return JsonPrimitive(true)
                    }
                },
            ),
        )

    override val providesOnlyKinds: Set<CodeActionKind>
        get() = setOf(actionKind)

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
                val choices = choicesResult.choices
                when {
                    choices.isEmpty() -> {}

                    choices.size == 1 -> emit(
                        refactoringCodeAction(documentUri, descriptorTitle, Payload.Data(selectionRange, choices.single()))
                    )

                    server.config.clientSupportsIntellijExtensions -> emit(
                        refactoringCodeAction(documentUri, descriptorTitle, Payload.Choose(selectionRange, choices))
                    )

                    else -> for (choice in choices) {
                        emit(
                            refactoringCodeAction(
                                documentUri = documentUri,
                                title = descriptorTitle + CHOICE_SEPARATOR + choice,
                                payload = Payload.Data(selectionRange, choice),
                            )
                        )
                    }
                }
            }

            is ChoicesResult.Error -> emit(
                refactoringCodeAction(documentUri, descriptorTitle, Payload.Error(choicesResult.errorMessage))
            )
        }
    }

    private fun refactoringCommand(documentUri: DocumentUri, title: @Nls String, payload: Payload): Command = Command(
        title = title,
        command = commandName,
        arguments = listOf(
            LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
            LSP.json.encodeToJsonElement<Payload>(payload),
        ),
    )

    private fun refactoringCodeAction(documentUri: DocumentUri, title: @Nls String, payload: Payload): CodeAction = CodeAction(
        title = title,
        kind = actionKind,
        command = refactoringCommand(documentUri, title, payload),
    )

    context(server: LSServer, analysisContext: LSAnalysisContext, handlerContext: LspHandlerContext)
    private suspend fun computeRefactoringResult(file: VirtualFile, data: Payload.Data): RefactoringResult {
        val writeContext = readAction {
            val document = file.findDocument() ?: return@readAction null
            val selection = data.selection.toTextRange(document)
            getWriteContext(file, selection, data.choice)
        } ?: return RefactoringResult.EMPTY

        return executeRefactoring(writeContext) ?: RefactoringResult.EMPTY
    }

    /**
     * Calculates the available refactoring choices in the given [selectedRange] and the adjusted selection.
     * @return null if it is impossible to do the refactoring in the given position,
     * [LSRefactoringMemberProviderBase.ChoicesResult.Choices] when refactoring is possible,
     * or [LSRefactoringMemberProviderBase.ChoicesResult.Error] if the refactoring is impossible and
     * the error should be displayed to the user.
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getChoices(file: VirtualFile, selectedRange: TextRange): ChoicesResult?

    /**
     * Creates a context for the refactoring based on the given [choice] and pre-computed [selection].
     * @return context for the refactoring. It is expected that context can always be retrieved since the [choice] was shown to the user.
     * @param selection the adjusted selection as computed by [getChoices], not the raw client range
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getWriteContext(file: VirtualFile, selection: TextRange, choice: String): Context

    /**
     * Executes the refactoring on the given [context].
     * After this method is called, the member is refactored and computation of the edits is performed.
     *
     * @return the element to which the caret position should be navigated
     */
    context(server: LSServer, analysisContext: LSAnalysisContext, handlerContext: LspHandlerContext)
    protected abstract suspend fun executeRefactoring(context: Context): RefactoringResult?

    /**
     * @property startRename when true, and the client declares `intellijExtensions`, the client is asked to
     *   start an inline rename at [navigationRange] after the edit is applied
     */
    data class RefactoringResult(
        val changes: List<FileChange>,
        val navigationRange: Range?,
        val startRename: Boolean = false,
    ) {
        companion object {
            val EMPTY: RefactoringResult = RefactoringResult(emptyList(), null)
        }
    }

    protected sealed interface ChoicesResult {
        data class Choices(val choices: List<@Nls String>, val selection: TextRange) : ChoicesResult
        data class Error(val errorMessage: @Nls String) : ChoicesResult
    }

    @Serializable
    private sealed interface Payload {
        @Serializable
        data class Data(val selection: Range, val choice: @Nls String) : Payload

        @Serializable
        data class Error(val message: @Nls String) : Payload

        @Serializable
        data class Choose(val selection: Range, val choices: List<@Nls String>) : Payload
    }
}
