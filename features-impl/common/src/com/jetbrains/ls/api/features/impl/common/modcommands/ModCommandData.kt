// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
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
import com.jetbrains.ls.api.core.util.uri
import com.jetbrains.ls.snapshot.api.impl.core.ChooseActionSessionComponent
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
import com.jetbrains.lsp.protocol.URI
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
sealed class ModCommandData {
    @Serializable
    data object Nothing : ModCommandData()

    @Serializable
    data class Composite(val commands: List<ModCommandData>) : ModCommandData()

    @Serializable
    data class Navigate(val fileUrl: String, val selectionStart: Int, val selectionEnd: Int, val caret: Int) : ModCommandData()

    @Serializable
    data class Snippet(val fileUrl: String, val vars: List<SnippetVar> = listOf()) : ModCommandData() {
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
    data class CreateFile(val fileUrl: String, val content: Content) : ModCommandData() {
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
    data class DeleteFile(val fileUrl: String) : ModCommandData()

    @Serializable
    data class MoveFile(val fileUrl: String, val targetUrl: String) : ModCommandData()

    @Serializable
    data class UpdateFileText(val fileUrl: String, val oldText: String, val newText: String) : ModCommandData()

    @Serializable
    data class DisplayMessage(val message: String, val messageKind: ModDisplayMessage.MessageKind) : ModCommandData()

    @Serializable
    data class CopyToClipboard(val content: String) : ModCommandData()

    /**
     * A [ModChooseAction] asks the UI to present a chooser of further actions. LSP has no native primitive for
     * this (see https://github.com/microsoft/language-server-protocol/issues/994), so it is modeled via the
     * custom `intellij/chooseAction` notification: the client shows a menu of [entries] and, once the user picks
     * one, invokes the `chooseModCommandAction` command with [sessionId] and the chosen [Entry.index]. The live
     * choice actions themselves cannot be serialized, so they are kept server-side in
     * [ChooseActionSessionComponent][com.jetbrains.ls.snapshot.api.impl.core.ChooseActionSessionComponent],
     * keyed by [sessionId]. Only clients that declare `intellijExtensions` can handle it; [from] aborts for the others.
     */
    @Serializable
    data class ChooseAction(val sessionId: Long, val title: String, val entries: List<Entry>) : ModCommandData() {
        @Serializable
        data class Entry(val index: Int, val name: String)
    }


    companion object {
        private val LOG = logger<ModCommandData>()

        /** A selectable [ModChooseAction] option: its original [index], the [action], and its presentation [name]. */
        private data class Choice(val index: Int, val action: ModCommandAction, val name: String)

        fun from(
            command: ModCommand,
            actionContext: ActionContext,
            server: LSServer? = null,
        ): ModCommandData? = when (command) {
            is ModNothing -> Nothing
            is ModCompositeCommand -> {
                val commands = command.commands.map { from(it, actionContext, server) ?: return null }
                Composite(
                    if (commands.all { it is UpdateFileText }) {
                        commands.mapIndexed { index, data -> data to index }
                            .sortedWith(compareBy({ (it.first as UpdateFileText).fileUrl }, { it.second }))
                            .map { it.first }
                    } else {
                        commands
                    }
                )
            }
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
            // Relies on the custom `intellij/chooseAction` notification and a server-side session cache, so
            // only clients that declare `intellijExtensions` can handle it; abort for the others.
            is ModChooseAction -> when {
                server?.config?.clientSupportsIntellijExtensions == true && !command.isEmpty -> {
                    // Selectable choices (those with an available presentation), keeping the original index so it
                    // stays aligned with the stored action list used to look the choice up later.
                    val choices = command.actions.mapIndexedNotNull { index, action ->
                        val name = runCatching { action.getPresentation(actionContext)?.name }.getOrNull()
                        name?.let { Choice(index, action, it) }
                    }
                    when (choices.size) {
                        0 -> null
                        // A single choice needs no menu: perform it right away and convert its result. This also
                        // collapses nested single-choice chains, since the performed command is fed back into `from`.
                        1 -> {
                            val choice = choices.single()
                            runCatching {
                                choice.action.perform(actionContext)
                            }.getOrElse {
                                LOG.warn("Failed to perform the single choice action ${choice.action}", it)
                                null
                            }?.let { from(it, actionContext, server) }
                        }
                        else -> {
                            val session = ChooseActionSession(
                                fileUri = actionContext.file.virtualFile.uri,
                                offset = actionContext.offset,
                                selection = actionContext.selection,
                                title = command.title,
                                actions = command.actions.toList(),
                            )
                            val id = server[ChooseActionSessionComponent].register(session)
                            val entries = choices.map { ChooseAction.Entry(it.index, it.name) }
                            ChooseAction(id.id, command.title, entries)
                        }
                    }
                }
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

        is ModCommandData.ChooseAction -> client.notify(
            notificationType = ShowChooseActionMenuNotification,
            params = ShowChooseActionMenuParams(
                sessionId = command.sessionId,
                title = command.title,
                entries = command.entries.map { ChooseActionMenuEntry(it.index, it.name) },
            ),
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

/**
 * The live payload stored in
 * [ChooseActionSessionComponent][com.jetbrains.ls.snapshot.api.impl.core.ChooseActionSessionComponent]
 * for a shown [ModChooseAction] menu, recovered when the client reports the user's selection.
 */
class ChooseActionSession(
    val fileUri: URI,
    val offset: Int,
    val selection: TextRange,
    val title: String,
    val actions: List<ModCommandAction>,
)

@Serializable
data class ChooseActionMenuEntry(val index: Int, val name: String)

@Serializable
data class ShowChooseActionMenuParams(val sessionId: Long, val title: String, val entries: List<ChooseActionMenuEntry>)

/**
 * A custom server -> client notification (used by the ModCommand [ModChooseAction]) asking the client to show a
 * chooser menu of [ShowChooseActionMenuParams.entries]. Once the user picks an entry, the client is expected to
 * invoke the `chooseModCommandAction` command with the [ShowChooseActionMenuParams.sessionId] and the chosen
 * [ChooseActionMenuEntry.index]. Clients that do not support it simply ignore it.
 */
val ShowChooseActionMenuNotification: NotificationType<ShowChooseActionMenuParams> =
    NotificationType("intellij/chooseAction", ShowChooseActionMenuParams.serializer())
