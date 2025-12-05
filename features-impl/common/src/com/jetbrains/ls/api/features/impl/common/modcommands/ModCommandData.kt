// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.modcommand.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.*
import com.jetbrains.lsp.protocol.ApplyEditRequests.ApplyEdit
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

    @Serializable
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
    data class DisplayMessage(val message: String, val messageKind: ModDisplayMessage.MessageKind) : ModCommandData


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
            is ModRegisterTabOut -> Nothing // We can safely skip the tab-out command
            // Highlighting could be important, but usually it's an additional helpful thing, not an essential one, so let's skip it for now
            is ModHighlight -> Nothing
            else -> {
                LOG.debug("Unsupported command $command")
                null
            }
        }
    }
}

suspend fun executeCommand(command: ModCommandData, client: LspClient, changedFiles: MutableMap<String, String> = mutableMapOf()) {
    when (command) {
        is ModCommandData.Nothing -> {}

        is ModCommandData.CreateFile -> {
            when (command.content) {
                is ModCommandData.CreateFile.Content.Text -> {
                    client.request(
                        requestType = ApplyEdit,
                        params = ApplyWorkspaceEditParams(
                            label = "Create ${command.fileUrl}",
                            edit = WorkspaceEdit(
                                documentChanges = listOf(
                                    CreateFile(DocumentUri(command.fileUrl.intellijUriToLspUri())),
                                    TextDocumentEdit(
                                        textDocument = TextDocumentIdentifier(DocumentUri(command.fileUrl.intellijUriToLspUri())),
                                        edits = listOf(TextEdit(Range.BEGINNING, command.content.text)),
                                    ),
                                ),
                            ),
                        ),
                    )
                    changedFiles[command.fileUrl] = command.content.text
                }

                else -> error("Unsupported content ${command.content}")
            }
        }

        is ModCommandData.DeleteFile -> {
            client.request(
                requestType = ApplyEdit,
                params = ApplyWorkspaceEditParams(
                    label = "Delete ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(DeleteFile(DocumentUri(command.fileUrl.intellijUriToLspUri()))),
                    ),
                ),
            )
        }

        is ModCommandData.MoveFile -> {
            client.request(
                requestType = ApplyEdit,
                params = ApplyWorkspaceEditParams(
                    label = "Move ${command.fileUrl} to ${command.targetUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(
                            RenameFile(
                                DocumentUri(command.fileUrl.intellijUriToLspUri()), DocumentUri(command.targetUrl.intellijUriToLspUri())
                            ),
                        ),
                    ),
                ),
            )
        }

        is ModCommandData.UpdateFileText -> {
            client.request(
                requestType = ApplyEdit,
                params = ApplyWorkspaceEditParams(
                    label = "Update ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        changes = mapOf(
                            DocumentUri(command.fileUrl.intellijUriToLspUri()) to computeTextEdits(
                                oldText = command.oldText,
                                newText = command.newText,
                            ),
                        ),
                    ),
                ),
            )
            changedFiles[command.fileUrl] = command.newText
        }

        is ModCommandData.Navigate -> {
            val selectionStart = command.selectionStart.takeIf { it != -1 } ?: command.caret
            val selectionEnd = command.selectionEnd.takeIf { it != -1 } ?: command.caret
            var selection: Range? = null
            if (selectionStart != -1 && selectionEnd != -1) {
                val doc = changedFiles[command.fileUrl]?.let { DocumentImpl(it) } ?: command.fileUrl.intellijUriToLspUri().findVirtualFile()
                    ?.findDocument()

                if (doc != null) {
                    selection = Range(
                        start = doc.positionByOffset(selectionStart),
                        end = doc.positionByOffset(selectionEnd),
                    )
                }
            }

            client.request(
                requestType = ShowDocument,
                params = ShowDocumentParams(
                    uri = command.fileUrl.intellijUriToLspUri(),
                    external = false,
                    takeFocus = selection != null,
                    selection = selection,
                ),
            )
        }

        is ModCommandData.Composite -> command.commands.forEach { executeCommand(it, client, changedFiles) }

        is ModCommandData.DisplayMessage -> client.request(
            requestType = Window.ShowMessageRequest,
            params = ShowMessageRequestParams(
                type = when (command.messageKind) {
                    ModDisplayMessage.MessageKind.ERROR -> MessageType.Error
                    ModDisplayMessage.MessageKind.INFORMATION -> MessageType.Info
                },
                message = command.message,
                actions = null,
            ),
        )
    }
}