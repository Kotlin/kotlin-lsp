// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.modcommand.*
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests.ApplyEdit
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.ShowDocument
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageRequestParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.Window
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
sealed interface ModCommandData {
    @Serializable
    data object Nothing : ModCommandData

    @Serializable
    data class Composite(val commands: List<ModCommandData>) : ModCommandData

    @Serializable
    data class Navigate(val fileUrl: String, val selectionStart: Int, val selectionEnd: Int, val caret: Int) : ModCommandData

    data class CreateFile(val fileUrl: String, val content: Content) : ModCommandData {
        @Serializable
        sealed interface Content {
            @Serializable
            data object Directory : Content

            @Serializable
            data class Text(val text: String) : Content

            @Serializable
            data class Binary(val base64: String) : Content
        }
    }

    @Serializable
    data class DeleteFile(val fileUrl: String) : ModCommandData

    @Serializable
    data class MoveFile(val fileUrl: String, val targetUrl: String) : ModCommandData

    @Serializable
    data class UpdateFileText(val fileUrl: String, val oldText: String, val newText: String) : ModCommandData

    @Serializable
    data class DisplayMessage(val message: String, val kind: ModDisplayMessage.MessageKind) : ModCommandData


    companion object {
        private val LOG = logger<ModCommandData>()

        fun from(command: ModCommand): ModCommandData? = when (command) {
            is ModNothing -> Nothing
            is ModCompositeCommand -> Composite(command.commands.map { from(it) ?: return null })
            is ModNavigate -> Navigate(command.file.url, command.selectionStart, command.selectionEnd, command.caret)
            is ModCreateFile -> CreateFile(
                command.file.url, when (val c = command.content) {
                    is ModCreateFile.Directory -> CreateFile.Content.Directory
                    is ModCreateFile.Text -> CreateFile.Content.Text(c.text)
                    is ModCreateFile.Binary -> CreateFile.Content.Binary(Base64.getEncoder().encodeToString(c.bytes))
                }
            )
            is ModDeleteFile -> DeleteFile(command.file.url)
            is ModMoveFile -> MoveFile(command.file.url, command.targetFile.url.replace("mock://", "file://"))
            is ModUpdateFileText -> UpdateFileText(command.file.url, command.oldText, command.newText)
            is ModDisplayMessage -> DisplayMessage(command.messageText, command.kind)
            else -> {
                LOG.debug("Unsupported command $command")
                null
            }
        }
    }
}

suspend fun executeCommand(command: ModCommandData, client: LspClient) {
    when (command) {
        is ModCommandData.Nothing -> {}

        is ModCommandData.CreateFile ->
            when (command.content) {
                is ModCommandData.CreateFile.Content.Text ->
                    client.request(
                        ApplyEdit,
                        ApplyWorkspaceEditParams(
                            label = "Create ${command.fileUrl}",
                            edit = WorkspaceEdit(
                                documentChanges = listOf(
                                    CreateFile(DocumentUri(URI(command.fileUrl))),
                                    TextDocumentEdit(
                                        textDocument = TextDocumentIdentifier(DocumentUri(URI(command.fileUrl))),
                                        edits = listOf(TextEdit(Range.BEGINNING, command.content.text))
                                    )
                                ),
                            )
                        )
                    )
                else -> error("Unsupported content ${command.content}")
            }


        is ModCommandData.DeleteFile ->
            client.request(
                ApplyEdit,
                ApplyWorkspaceEditParams(
                    label = "Delete ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(DeleteFile(DocumentUri(URI(command.fileUrl)))),
                    )
                )
            )

        is ModCommandData.MoveFile ->
            client.request(
                ApplyEdit,
                ApplyWorkspaceEditParams(
                    label = "Move ${command.fileUrl} to ${command.targetUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(RenameFile(DocumentUri(URI(command.fileUrl)), DocumentUri(URI(command.targetUrl)))),
                    )
                )
            )

        is ModCommandData.UpdateFileText ->
            client.request(
                ApplyEdit,
                ApplyWorkspaceEditParams(
                    label = "Update ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        changes = mapOf(DocumentUri(URI(command.fileUrl)) to computeTextEdits(command.oldText, command.newText))
                    )
                )
            )

        is ModCommandData.Navigate -> {
            val uri = URI(command.fileUrl)

            client.request(
                ShowDocument,
                ShowDocumentParams(
                    uri = uri,
                    external = false,
                    takeFocus = command.selectionStart != -1 || command.caret != -1,
                    // TODO: LSP-163 support selection and caret taking into account the effects of the previously applied commands
                )
            )
        }

        is ModCommandData.Composite -> command.commands.forEach { executeCommand(it, client) }
        is ModCommandData.DisplayMessage ->
            client.request(Window.ShowMessageRequest, ShowMessageRequestParams(
                type = when (command.kind) {
                    ModDisplayMessage.MessageKind.ERROR -> MessageType.Error
                    ModDisplayMessage.MessageKind.INFORMATION -> MessageType.Info
                },
                message = command.message,
                actions = null)
            )
    }
}