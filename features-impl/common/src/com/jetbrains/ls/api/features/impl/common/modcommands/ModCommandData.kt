// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCompositeCommand
import com.intellij.modcommand.ModCopyToClipboard
import com.intellij.modcommand.ModCreateFile
import com.intellij.modcommand.ModDeleteFile
import com.intellij.modcommand.ModDisplayMessage
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModMoveFile
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModNothing
import com.intellij.modcommand.ModRegisterTabOut
import com.intellij.modcommand.ModStartTemplate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests.ApplyEdit
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.ShowDocument
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageRequestParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.Window
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.Serializable
import java.util.Base64

private val snippetEscapeCharacters = Regex("""[\\}$]""")
private val snippetChoiceEscapeCharacters = Regex("""[\\}$,|]""")
private const val SNIPPET_REPLACEMENT = $$"\\\\$0"

/**
 * **Note**: [ModChooseAction] cannot be modeled with [ModCommandData];
 * see [ModChooseActionChain][com.jetbrains.ls.api.features.impl.common.modcommands.ModChooseActionChain]
 * for details and utilities related to that.
 */
@Serializable
sealed interface ModCommandData {
    @Serializable
    data object Nothing : ModCommandData

    @Serializable
    data class Composite(val commands: List<ModCommandData>) : ModCommandData

    @Serializable
    data class Navigate(val fileUrl: String, val selectionStart: Int, val selectionEnd: Int, val caret: Int) : ModCommandData

    @Serializable
    data class Snippet(val fileUrl: String, val vars: List<SnippetVar> = listOf()) : ModCommandData {
        fun add(vararg vars: SnippetVar): Snippet = copy(vars = this.vars + vars.toList())

        fun toTextEdit(text: Document): TextEdit {
            var start = vars.minOf { it.start }
            val end = vars.maxOf { it.end }
            val startLine = text.getLineNumber(start)
            val endLine = text.getLineNumber(end)
            if (startLine != endLine) {
                // It looks like when we create a multiline text edit for snippet, VS Code adds automatic indent.
                // Let's start from the line beginning to work this around.
                start = text.getLineStartOffset(startLine)
            }
            val snippet = toString(text.getText(TextRange(start, end)), start)
            return TextEdit(Range(text.positionByOffset(start), text.positionByOffset(end)), snippet = snippet)
        }
        
        fun toString(text: String, delta: Int = 0): String {
            val sortedVars = vars.sortedBy { v -> v.start }
            var pos = 0
            val sb = StringBuilder()
            for (v in sortedVars) {
                val nextPos = v.start - delta
                sb.append(
                    text.substring(pos, nextPos).replace(
                        snippetEscapeCharacters,
                        SNIPPET_REPLACEMENT
                    )
                ).append(v.toString(text, delta))
                pos = v.end - delta
            }
            sb.append(
                text.substring(pos).replace(
                    snippetEscapeCharacters,
                    SNIPPET_REPLACEMENT
                )
            )
            return sb.toString()
        }

    }

    @Serializable
    data class SnippetVar(val start: Int, val end: Int, val name: Int, val choices: List<String> = listOf()) {
        fun toString(text: String, delta: Int = 0): String {
            return $$"${" +
                    name +
                    (if (start == end || !choices.isEmpty()) ""
                    else ":" + text.substring(start - delta, end - delta).replace(snippetEscapeCharacters, SNIPPET_REPLACEMENT)) +
                    (if (choices.isEmpty()) ""
                    else choices.joinToString(",", "|", "|") { it.replace(snippetChoiceEscapeCharacters, SNIPPET_REPLACEMENT) }) +
                    "}"
        }

    }
    
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

    @Serializable
    data class CopyToClipboard(val content: String) : ModCommandData


    companion object {
        private val LOG = logger<ModCommandData>()

        fun from(command: ModCommand, server: LSServer? = null): ModCommandData? = when (command) {
            is ModNothing -> Nothing
            is ModCompositeCommand -> Composite(command.commands.map { from(it, server) ?: return null })
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
            // Relies on the custom `intellij/copyToClipboard` notification, so only clients,
            // which declare `intellijExtensions` can handle it; abort for the others.
            is ModCopyToClipboard -> when {
                server?.config?.clientSupportsIntellijExtensions == true ->
                    CopyToClipboard(command.content)
                else -> null
            }
            is ModRegisterTabOut -> Nothing // We can safely skip the tab-out command
            // Highlighting could be important, but usually it's an additional helpful thing, not an essential one, so let's skip it for now
            is ModHighlight -> Nothing
            // Templates are not fully supported yet
            is ModStartTemplate -> when {
                server?.config?.clientSupportsSnippetWorkspaceEdit == true -> convertTemplate(command)
                command.optional -> Nothing
                else -> null
            }
            else -> {
                LOG.debug("Unsupported command $command")
                null
            }
        }

        fun convertTemplate(cmd: ModStartTemplate): Snippet {
            val vars = mutableListOf<SnippetVar>()
            val map = mutableMapOf<String, Int>()
            var i = 0
            for (field in cmd.fields) {
                when (field) {
                    is ModStartTemplate.EndField -> {
                        val pos = field.range.startOffset
                        vars.add(SnippetVar(pos, pos, 0))
                    }

                    is ModStartTemplate.ExpressionField -> {
                        val start = field.range.startOffset
                        val end = field.range.endOffset
                        val varName = field.varName
                        val num = if (varName == null) ++i else map.computeIfAbsent(varName) { ++i }
                        val lookupStrings = field.expression().staticLookupStrings
                        vars.add(SnippetVar(start, end, num, lookupStrings))
                    }

                    is ModStartTemplate.DependantVariableField -> {
                        //skipped, will be processed lately, after collecting variables
                    }
                }
            }
            // process DependantVariableField and pass them as SnippetVar.
            for (field in cmd.fields) {
                if (field !is ModStartTemplate.DependantVariableField) continue
                val start = field.range.startOffset
                val end = field.range.endOffset
                val sourceNum = map[field.dependantVariableName]
                val sourceField = cmd.fields.asSequence()
                    .filterIsInstance<ModStartTemplate.ExpressionField>()
                    .firstOrNull { it.varName == field.dependantVariableName }
                // TODO: check newtext equality, see how it's implemented in LSJavaCompletionProvider
                val isMirror = sourceNum != null && sourceField != null
                val num = if (isMirror) sourceNum else map.computeIfAbsent(field.varName) { ++i }
                vars.add(SnippetVar(start, end, num))
            }
            return Snippet(cmd.file.url, vars)
        }
    }
}

context(_: LSAnalysisContext)
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
                // Skip directory creation commands. Subsequent 'create file' or 'move file' command will create missing directories anyway.
                is ModCommandData.CreateFile.Content.Directory -> {}

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

        is ModCommandData.Snippet -> {
            val doc =
                changedFiles[command.fileUrl]?.let { DocumentImpl(it) } ?: VirtualFileManager.getInstance().findFileByUrl(command.fileUrl)
                    ?.findDocument()
            if (doc != null) {
                client.request(
                    requestType = ApplyEdit,
                    params = ApplyWorkspaceEditParams(
                        label = "Run snippet in ${command.fileUrl}",
                        edit = WorkspaceEdit(
                            documentChanges = listOf(
                                TextDocumentEdit(
                                    textDocument = TextDocumentIdentifier(DocumentUri(command.fileUrl.intellijUriToLspUri())),
                                    edits = listOf(
                                        command.toTextEdit(doc)
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
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
                val doc = changedFiles[command.fileUrl]?.let { DocumentImpl(it) } ?: 
                    VirtualFileManager.getInstance().findFileByUrl(command.fileUrl)?.findDocument()

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

        is ModCommandData.CopyToClipboard -> client.notify(
            notificationType = CopyToClipboardNotification,
            params = CopyToClipboardParams(command.content),
        )
    }
}

@Serializable
data class CopyToClipboardParams(val content: String)

/**
 * A custom server -> client notification asking the client to put [CopyToClipboardParams.content]
 * into the system clipboard. There is no standard LSP request for clipboard access, so this mirrors
 * the `intellij/importLog` notification. Clients that do not support it simply ignore it.
 */
val CopyToClipboardNotification: NotificationType<CopyToClipboardParams> =
    NotificationType("intellij/copyToClipboard", CopyToClipboardParams.serializer())
