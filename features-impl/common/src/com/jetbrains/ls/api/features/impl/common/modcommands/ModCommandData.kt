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
import com.intellij.modcommand.ModEditOptions
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModLaunchEditorAction
import com.intellij.modcommand.ModMoveFile
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModNothing
import com.intellij.modcommand.ModRegisterTabOut
import com.intellij.modcommand.ModStartRename
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
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.impl.common.modcommands.LazyFix
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCommand
import com.jetbrains.ls.api.features.impl.common.modcommands.registerLazyFixes
import com.jetbrains.ls.api.features.impl.common.utils.showDocumentIfSupported
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer.computeTextEdits
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests.ApplyEdit
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageRequestParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.Window
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.Base64

private val LOG = logger<ModCommandData>()

private val snippetEscapeCharacters = Regex("""[\\}$]""")
private val snippetChoiceEscapeCharacters = Regex("""[\\}$,|]""")
private const val SNIPPET_REPLACEMENT = $$"\\\\$0"

/**
 * See [toModCommandFixes][com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes]
 * for fallback implementation of ModChooseAction support if client doesn't provide intellijExtensions capabilities.
 * A client which declares `lazyIntentions` alone gets no fallback, because the fallback has to perform the fix,
 * which is exactly what `lazyIntentions` defers.
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
     * A [ModStartRename] asks the editor to start an inline rename of the symbol between [selectionStart] and
     * [selectionEnd]. LSP has no server -> client request for that — `textDocument/rename` goes the other way, and
     * `workspace/executeCommand` cannot be sent to a client — so it is modeled as a navigation onto the symbol
     * followed by the custom `intellij/runEditorCommand` notification. Only clients that declare
     * `intellijExtensions` can handle it; for the others [from] degrades to a plain [Navigate], which at least
     * puts the caret on the symbol so the user can start the rename themselves.
     */
    @Serializable
    data class StartRename(val fileUrl: String, val selectionStart: Int, val selectionEnd: Int) : ModCommandData()

    /**
     * A [ModLaunchEditorAction] asks the editor to run one of its own actions, such as the code completion or the
     * parameter info popup. The action drives the editor UI and changes no document, and LSP has no server -> client
     * request for it, so it is modeled as the custom `intellij/runEditorCommand` notification, the same way
     * [StartRename] is. [actionId] is the IntelliJ action id; [editorCommandForAction] maps it to the command of
     * the client. Only clients that declare `intellijExtensions` can handle it; for the others [from] drops an
     * optional action and aborts on a mandatory one.
     */
    @Serializable
    data class LaunchEditorAction(val actionId: String) : ModCommandData()

    /**
     * A [ModChooseAction] asks the UI to present a chooser of further actions. LSP has no native primitive for
     * this (see https://github.com/microsoft/language-server-protocol/issues/994), so it is modeled via the
     * custom `intellij/chooseAction` notification: the client shows a menu of [entries] and, once the user picks
     * one, invokes the [Entry.action] of that entry. Only clients that declare `intellijExtensions` can handle
     * it; [from] aborts for the others.
     */
    @Serializable
    data class ChooseAction(val title: String, val entries: List<Entry>) : ModCommandData() {
        @Serializable
        data class Entry(val name: String, val action: LazyAction)
    }

    /**
     * A fix that the server offered without performing it, kept in
     * [LazyActionSessionComponent][com.jetbrains.ls.snapshot.api.impl.core.LazyActionSessionComponent] under
     * [sessionId], where [index] selects it among the fixes the same analysis found.
     *
     * Performing a fix is the expensive part of offering it, and the user never asks for it on most of the fixes
     * a file produces, so the client gets this reference instead of the command itself. Executing it routes back
     * to the server, which performs the fix and executes the command it produced. See
     * [LazyFix][com.jetbrains.ls.api.features.impl.common.modcommands.LazyFix] and
     * [executeLazyAction][com.jetbrains.ls.api.features.impl.common.modcommands.executeLazyAction].
     */
    @Serializable
    data class LazyAction(val sessionId: Long, val index: Int) : ModCommandData()


    companion object {
        /** A selectable [ModChooseAction] option: the [action] and its presentation [name]. */
        private data class Choice(val action: ModCommandAction, val name: String)

        fun from(
            command: ModCommand,
            actionContext: ActionContext,
            server: LSServer? = null,
        ): ModCommandData? = when (command) {
            is ModNothing -> Nothing
            is ModCompositeCommand -> Composite(command.commands.map { from(it, actionContext, server) ?: return null })
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
                    // Selectable choices, which are those with an available presentation.
                    val choices = command.actions.mapNotNull { action ->
                        val name = runCatching { action.getPresentation(actionContext)?.name }.getOrNull()
                        name?.let { Choice(action, it) }
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
                                LOG.error("Failed to perform the single choice action ${choice.action}", it)
                                null
                            }?.let { from(it, actionContext, server) }
                        }
                        else -> {
                            val virtualFile = actionContext.file.virtualFile ?: return null
                            // The choices are kept server-side and performed only once the user picks one, the
                            // same way a top-level fix is.
                            val fixes = choices.map { LazyFix.OfAction(it.name, it.action, actionContext) }
                            val actions = registerLazyFixes(server, virtualFile, fixes)
                            val entries = choices.mapIndexed { index, choice ->
                                ChooseAction.Entry(choice.name, actions[index])
                            }
                            ChooseAction(command.title, entries)
                        }
                    }
                }
                else -> null
            }
            is ModStartRename -> {
                val symbolRange = command.symbolRange()
                val range = symbolRange.nameIdentifierRange() ?: symbolRange.range()
                when {
                    server?.config?.clientSupportsIntellijExtensions == true ->
                        StartRename(command.file.url, range.startOffset, range.endOffset)
                    // The rename itself cannot be started, but the caret can still be put on the symbol.
                    else -> Navigate(command.file.url, range.startOffset, range.endOffset, range.startOffset)
                }
            }
            is ModLaunchEditorAction -> when {
                server?.config?.clientSupportsIntellijExtensions == true && editorCommandForAction(command.actionId) != null ->
                    LaunchEditorAction(command.actionId)
                // The action only drives the editor UI, so an optional one can be dropped.
                command.optional -> Nothing
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
            is ModEditOptions<*> -> when {
                // TODO: support ModEditOptions UI
                command.canUseDefaults -> from(command.nextCommand.apply(command.containerSupplier.get()), actionContext, server)
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

context(_: LSServer, _: LSAnalysisContext)
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
            // A template without tab stops has nothing to start, and its text is already in the document
            // (`toTextEdit` derives its range from the tab stops, so it cannot even be built).
            if (doc != null && command.vars.isNotEmpty()) {
                client.request(
                    requestType = ApplyEdit,
                    params = ApplyWorkspaceEditParams(
                        label = "Run snippet in ${command.fileUrl}",
                        edit = WorkspaceEdit(
                            documentChanges = listOf(
                                TextDocumentEdit(
                                    textDocument = TextDocumentIdentifier(DocumentUri(command.fileUrl.intellijUriToLspUri())),
                                    edits = listOf(command.toTextEdit(doc)),
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

            client.showDocumentIfSupported(
                ShowDocumentParams(
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

        is ModCommandData.StartRename -> {
            // `editor.action.rename` always renames at the caret, so the caret has to be moved onto the symbol first.
            executeCommand(
                command = ModCommandData.Navigate(
                    fileUrl = command.fileUrl,
                    selectionStart = command.selectionStart,
                    selectionEnd = command.selectionEnd,
                    caret = command.selectionStart,
                ),
                client = client,
                changedFiles = changedFiles,
            )
            client.notify(
                notificationType = RunEditorCommandNotification,
                params = RunEditorCommandParams(RENAME_EDITOR_COMMAND, uri = DocumentUri(command.fileUrl.intellijUriToLspUri())),
            )
        }

        // The surrounding edits already left the caret where the action has to run, so no navigation is needed.
        is ModCommandData.LaunchEditorAction -> when (val editorCommand = editorCommandForAction(command.actionId)) {
            // `from` only produces this for an action it can map, so a missing command means the two went out of sync.
            null -> LOG.error("No client editor command for the action ${command.actionId}")
            else -> client.notify(
                notificationType = RunEditorCommandNotification,
                params = RunEditorCommandParams(editorCommand.command),
            )
        }

        is ModCommandData.ChooseAction -> client.notify(
            notificationType = ShowChooseActionMenuNotification,
            params = ShowChooseActionMenuParams(
                title = command.title,
                entries = command.entries.map { entry ->
                    ChooseActionMenuEntry(entry.name, applyFixCommand(entry.action))
                },
            ),
        )

        // A lazy action is resolved by LSApplyFixCommandDescriptorProvider, before the analysis context this
        // function runs in is opened, so it never reaches here.
        is ModCommandData.LazyAction -> LOG.error("The lazy action $command was not resolved before execution")
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

@Serializable
data class RunEditorCommandParams(
    val command: String,
    val arguments: List<JsonElement> = emptyList(),
    val uri: DocumentUri? = null,
)

/**
 * A custom server -> client notification asking the client to run one of its own editor commands, the way a user
 * action would. Starting an inline rename ([ModCommandData.StartRename]) does not change the document but drives
 * the editor UI, and LSP has no way to express that: a server can neither send `workspace/executeCommand` nor
 * start such a session itself. Clients that do not support it simply ignore it.
 *
 * [RunEditorCommandParams.uri] names the document the command targets. The user can change the editor selection
 * before an asynchronous client handles the notification, so the client must drop the command when the selected
 * document differs.
 *
 * A live template needs no such command: [ModCommandData.Snippet] travels as a `SnippetTextEdit`, which is
 * standard LSP.
 */
val RunEditorCommandNotification: NotificationType<RunEditorCommandParams> =
    NotificationType("intellij/runEditorCommand", RunEditorCommandParams.serializer())

/** The client-side editor command that starts an inline rename of the symbol at the caret. */
const val RENAME_EDITOR_COMMAND: String = "editor.action.rename"

/**
 * The client-side editor command which matches the [ModLaunchEditorAction] [actionId], or `null` when the client
 * has no such command.
 *
 * `editor.action.triggerSuggest` and `editor.action.triggerParameterHints` are VSCode built-ins, not LSP, so only
 * a client which declares `intellijExtensions` is known to have them. The caller has to check that capability.
 */
fun editorCommandForAction(actionId: String): Command? = when (actionId) {
    ModLaunchEditorAction.ACTION_CODE_COMPLETION ->
        Command(LspServerBundle.message("command.completion"), "editor.action.triggerSuggest")
    ModLaunchEditorAction.ACTION_PARAMETER_INFO ->
        Command(LspServerBundle.message("command.parameter.info"), "editor.action.triggerParameterHints")
    else -> null
}

/**
 * One option of a [ModChooseAction] menu. [command] is what the client sends back to run the option, so the
 * client needs to know nothing about how the server keeps the option.
 */
@Serializable
data class ChooseActionMenuEntry(val name: String, val command: Command)

@Serializable
data class ShowChooseActionMenuParams(val title: String, val entries: List<ChooseActionMenuEntry>)

/**
 * A custom server -> client notification (used by the ModCommand [ModChooseAction]) asking the client to show a
 * chooser menu of [ShowChooseActionMenuParams.entries]. Once the user picks an entry, the client is expected to
 * invoke the [ChooseActionMenuEntry.command] of that entry. Clients that do not support it simply ignore it.
 */
val ShowChooseActionMenuNotification: NotificationType<ShowChooseActionMenuParams> =
    NotificationType("intellij/chooseAction", ShowChooseActionMenuParams.serializer())
