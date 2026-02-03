// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.extract

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.util.uri
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
 * Base class for extracting the variable in LSP.
 * Expected workflow:
 * 1. Fetch available options to extract the variable in the given range with [getChoices].
 * 2. Based on the user selection in step 1, create a context with [getWriteContext].
 * 3. Execute the variable extraction with [doExtractVariable].
 */
abstract class LSExtractVariableProviderBase<Context> : LSCodeActionProvider, LSCommandDescriptorProvider {
    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(commandDescriptor)

    override val providesOnlyKinds: Set<CodeActionKind>
        get() = setOf(ExtractActionKind.RefactorExtractVariable)

    private val commandDescriptor = LSCommandDescriptor(
        title = DESCRIPTOR_TITLE,
        name = COMMAND_NAME,
        executor = object : LSDocumentCommandExecutor {
            context(server: LSServer, handlerContext: LspHandlerContext)
            override suspend fun executeForDocument(
                documentUri: DocumentUri,
                otherArgs: List<JsonElement>
            ): List<TextEdit> {
                return server.withWriteAnalysisContext {
                    val (file, data) = readAction {
                        val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                        val data = otherArgs.firstOrNull()?.let { LSP.json.decodeFromJsonElement(ExtractVariableData.serializer(), it) }
                            ?: return@readAction null
                        virtualFile to data
                    } ?: return@withWriteAnalysisContext emptyList()
                    computeExtractVariableEdits(file, data)
                }
            }
        })

    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getCodeActions(params: CodeActionParams): Flow<CodeAction> = flow {
        val documentUri = params.textDocument.uri
        val choices = server.withAnalysisContext {
            readAction {
                val virtualFile = documentUri.findVirtualFile() ?: return@readAction null
                val document = virtualFile.findDocument() ?: return@readAction null
                getChoices(virtualFile, params.range.toTextRange(document)) ?: return@readAction null
            }
        } ?: return@flow

        for (choice in choices) {
            emit(
                CodeAction(
                    title = choice,
                    kind = ExtractActionKind.RefactorExtractVariable,
                    command = Command(
                        title = choice,
                        command = COMMAND_NAME,
                        arguments = listOf<JsonElement>(
                            LSP.json.encodeToJsonElement<DocumentUri>(documentUri),
                            LSP.json.encodeToJsonElement<ExtractVariableData>(
                                ExtractVariableData.serializer(),
                                ExtractVariableData(params.range, choice)
                            ),
                        ),
                    )
                )
            )
        }
    }

    context(server: LSServer, analysisContext: LSAnalysisContext)
    private suspend fun computeExtractVariableEdits(file: VirtualFile, data: ExtractVariableData): List<TextEdit> {
        val (writeContext, oldDocumentText) = readAction {
            val document = file.findDocument() ?: return@readAction null
            val range = data.range.toTextRange(document)
            getWriteContext(file, range, data.choice) to document.text
        } ?: return emptyList()

        server.withWritableFile(file.uri) {
            doExtractVariable(writeContext)
        }

        return readAction {
            val document = file.findDocument() ?: return@readAction emptyList()
            computeTextEdits(oldDocumentText, document.text)
        }
    }

    /**
     * Calculates the available extraction types in the given [selectedRange].
     * @return null if it is impossible to extract the variable in position, list of displayable choices otherwise
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getChoices(file: VirtualFile, selectedRange: TextRange): List<String>?

    /**
     * Creates a context for the variable extraction based on the given [choice].
     * @return context for the variable extraction. It is expected that context can always be retrieved since the [choice] was shown to the user.
     */
    @RequiresReadLock
    context(analysisContext: LSAnalysisContext)
    protected abstract fun getWriteContext(file: VirtualFile, selectedRange: TextRange, choice: String): Context

    /**
     * Executes the variable extraction on the given [context].
     * After this method is called, the variable is extracted and computation of the edits is performed.
     */
    context(server: LSServer, analysisContext: LSAnalysisContext)
    protected abstract suspend fun doExtractVariable(context: Context)


    @Serializable
    data class ExtractVariableData(val range: Range, val choice: String)

    companion object {
        private const val DESCRIPTOR_TITLE = "Extract to local variable"
        private const val COMMAND_NAME = "refactor.extract.variable"
    }
}
