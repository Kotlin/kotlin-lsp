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
import com.intellij.modcommand.ModShowConflicts
import com.intellij.modcommand.ModStartRename
import com.intellij.modcommand.ModStartTemplate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.modcommand.ModUpdateSystemOptions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findDocument
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.fileName
import com.jetbrains.ls.api.core.util.getLspLocationForDefinition
import com.jetbrains.ls.api.core.util.intellijUriToLspUri
import com.jetbrains.ls.api.core.util.lspUriToIntellijUri
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.util.toLspRange
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.applyEditOrRefuse
import com.jetbrains.ls.api.features.commands.refuseStaleDocument
import com.jetbrains.ls.api.features.commands.versionedEdit
import com.jetbrains.ls.api.features.impl.common.modcommands.DocumentStamp
import com.jetbrains.ls.api.features.impl.common.modcommands.DocumentStamps
import com.jetbrains.ls.api.features.impl.common.modcommands.LSSystemOptionsCommandProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.LazyFix
import com.jetbrains.ls.api.features.impl.common.modcommands.applyFixCommand
import com.jetbrains.ls.api.features.impl.common.modcommands.registerLazyFixes
import com.jetbrains.ls.api.features.impl.common.modcommands.stampsOf
import com.jetbrains.ls.api.features.impl.common.utils.showDocumentIfSupported
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageRequestParams
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.Window
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private val LOG = logger<ModCommandData>()

private val snippetEscapeCharacters = Regex("""[\\}$]""")
private val snippetChoiceEscapeCharacters = Regex("""[\\}$,|]""")
private const val SNIPPET_REPLACEMENT = $$"\\\\$0"
private const val MOCK_PROTOCOL_PREFIX = "mock://"

/**
 * See [toModCommandFixes][com.jetbrains.ls.api.features.impl.common.modcommands.toModCommandFixes]
 * for fallback implementation of ModChooseAction support if client doesn't provide actionChooser capabilities.
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
    data class Edit(val from: Int, val to: Int, val text: String) {
        /** The LSP form of the edit, with the offsets of [document] mapped to lines and columns. */
        fun toTextEdit(document: Document): TextEdit = TextEdit(TextRange(from, to).toLspRange(document), text)

    }

    @Serializable
    data class UpdateFileText(val fileUrl: String, val edits: List<Edit>) : ModCommandData() {
        /** The text after the [edits] are applied to [oldText]. The edits are sorted by offset, do not overlap, and refer to [oldText]. */
        fun applyEdits(oldText: String): String {
            val result = StringBuilder()
            var pos = 0
            for ((from, to, text) in edits) {
                result.append(oldText, pos, from).append(text)
                pos = to
            }
            return result.append(oldText, pos, oldText.length).toString()
        }

    }

    @Serializable
    data class DisplayMessage(val message: String, val messageKind: ModDisplayMessage.MessageKind) : ModCommandData()

    @Serializable
    data class CopyToClipboard(val content: String) : ModCommandData()

    /**
     * A [ModStartRename] asks the editor to start an inline rename of the symbol between [selectionStart] and
     * [selectionEnd]. LSP has no server -> client request for that — `textDocument/rename` goes the other way, and
     * `workspace/executeCommand` cannot be sent to a client — so it is modeled as a navigation onto the symbol
     * followed by the custom `intellij/runEditorCommand` notification.
     * Clients declare `runEditorCommand` to handle it. For other clients, [from] returns [Navigate] to place the caret on the symbol.
     */
    @Serializable
    data class StartRename(val fileUrl: String, val selectionStart: Int, val selectionEnd: Int) : ModCommandData()

    /**
     * A [ModLaunchEditorAction] asks the editor to run one of its own actions, such as the code completion or the
     * parameter info popup. The action drives the editor UI and changes no document, and LSP has no server -> client
     * request for it, so it is modeled as the custom `intellij/runEditorCommand` notification, the same way
     * [StartRename] is. [actionId] is the IntelliJ action id; [editorCommandForAction] maps it to the command of
     * the client.
     * Clients declare `runEditorCommand` to handle it. For other clients, [from] drops an optional action and aborts on a mandatory action.
     */
    @Serializable
    data class LaunchEditorAction(val actionId: String) : ModCommandData()

    /**
     * A [ModChooseAction] asks the UI to present a chooser of further actions.
     * LSP has no native primitive for this (see https://github.com/microsoft/language-server-protocol/issues/994).
     * The custom `intellij/chooseAction` notification asks the client to show a menu of [entries].
     * Once the user picks one, the client invokes its [Entry.action].
     * Only clients that declare `actionChooser` can handle it; [from] aborts for the others.
     */
    @Serializable
    data class ChooseAction(val title: String, val entries: List<Entry>) : ModCommandData() {
        @Serializable
        data class Entry(val name: String, val action: LazyAction)
    }

    /**
     * A [ModShowConflicts] asks the user to confirm a fix which has known problems. The command carries no edit.
     * The edits are the commands after it in the enclosing [Composite], so a `Cancel` answer stops that
     * [Composite]. Nothing has to be undone, because no edit ran yet.
     *
     * It relies on the custom [ShowConflictsRequest], so only a client which declares `conflictView` can
     * handle it. [from] aborts for the others. A `window/showMessageRequest` could ask the same question.
     * A client which shows no dialog answers `null` to it, and the fix would then do nothing without saying why.
     */
    @Serializable
    data class ShowConflicts(val conflicts: List<Conflict>) : ModCommandData() {
        /**
         * One problem of the fix. [messages] describes it as plain text. [location] points at the code which
         * has the problem, and is `null` when that code has no document of its own.
         */
        @Serializable
        data class Conflict(val messages: List<String>, val location: Location? = null)
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

        /**
         * A [ModCommandAction] builds a new file in a copy of the directory, so the file gets the `mock`
         * protocol. The path is correct, and the client knows the `file` protocol only. A URL with another
         * protocol stays unchanged.
         */
        private fun String.toFileUrl(): String =
            if (startsWith(MOCK_PROTOCOL_PREFIX)) "file://" + removePrefix(MOCK_PROTOCOL_PREFIX) else this

        /**
         * The [ModCommandData] which is equivalent to [command], or `null` when the client cannot apply it.
         *
         * [actionContext] describes the document the command was built against. [server] supplies the client
         * capabilities. A command which has no LSP equivalent needs a custom `intellij` request or notification.
         * A client without the required capability gets a degraded form, or `null`.
         * Each branch documents the form it falls back to.
         *
         * A `null` result aborts the whole conversion, including the enclosing [ModCompositeCommand]. The caller
         * must then offer no fix, because a half-applied command can leave the file broken.
         *
         * This method can perform an action. A [ModChooseAction] with one selectable choice has no menu to show,
         * so the choice runs at once and its result is converted.
         *
         * The completion path does not start here.
         * [split][com.jetbrains.ls.api.features.impl.common.completion.ModCompletionCommandConverter.split] turns
         * the leading edits into the text of the completion item. Only the rest reaches this method, through
         * [toCommand][com.jetbrains.ls.api.features.impl.common.completion.ModCompletionCommandConverter.toCommand],
         * which converts [ModStartRename] and [ModLaunchEditorAction] itself.
         */
        fun from(
            command: ModCommand,
            actionContext: ActionContext,
            server: LSServer? = null,
        ): ModCommandData? = when (command) {
            is ModNothing -> Nothing
            is ModCompositeCommand -> Composite(command.commands.map { from(it, actionContext, server) ?: return null })
            is ModNavigate -> Navigate(command.file.url.toFileUrl(), command.selectionStart, command.selectionEnd, command.caret)
            is ModCreateFile -> CreateFile(
                command.file.url.toFileUrl(), when (val c = command.content) {
                    is ModCreateFile.Directory -> CreateFile.Content.Directory
                    is ModCreateFile.Text -> CreateFile.Content.Text(c.text)
                    is ModCreateFile.Binary -> CreateFile.Content.Binary(Base64.getEncoder().encodeToString(c.bytes))
                }
            )

            is ModDeleteFile -> DeleteFile(command.file.url)
            is ModMoveFile -> MoveFile(command.file.url, command.targetFile.url.toFileUrl())
            // A file outside the index of the server is not in its virtual file system, so a command addresses it by a
            // `mock` file of the same path.
            is ModUpdateFileText -> UpdateFileText(command.file.url.toFileUrl(), command.toEdits())
            is ModDisplayMessage -> DisplayMessage(command.messageText, command.kind)
            // Relies on the custom `intellij/copyToClipboard` notification, so only clients,
            // which declare `copyToClipboard` can handle it; abort for the others.
            is ModCopyToClipboard -> when {
                server?.config?.clientSupportsCopyToClipboard == true ->
                    CopyToClipboard(command.content)
                else -> null
            }
            // Relies on the custom `intellij/chooseAction` notification and a session cache on the server.
            // Only clients that declare `actionChooser` can handle it; abort for the others.
            is ModChooseAction -> when {
                server?.config?.clientSupportsActionChooser == true && !command.isEmpty -> {
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
                    server?.config?.clientSupportsRunEditorCommand == true ->
                        StartRename(command.file.url, range.startOffset, range.endOffset)
                    // The rename itself cannot be started, but the caret can still be put on the symbol.
                    else -> Navigate(command.file.url, range.startOffset, range.endOffset, range.startOffset)
                }
            }
            is ModLaunchEditorAction -> when {
                server?.config?.clientSupportsRunEditorCommand == true && editorCommandForAction(command.actionId) != null ->
                    LaunchEditorAction(command.actionId)
                // The action only drives the editor UI, so an optional one can be dropped.
                command.optional -> Nothing
                else -> null
            }
            is ModShowConflicts -> when {
                command.isEmpty -> Nothing
                // The client cannot ask the user, so the fix goes on, and the user is told that it can break the code.
                server?.config?.clientSupportsConflictView != true -> DisplayMessage(
                    (listOf(LspServerBundle.message("conflicts.title")) +
                     command.conflicts.flatMap { [element, conflict] ->
                         val place = element.getLspLocationForDefinition()?.let { "[${it.uri.uri.fileName}:${it.range.start.line + 1}] " }.orEmpty()
                         conflict.messages.map { place + StringUtil.removeHtmlTags(it, true).removeSuffix(".") + "." }
                     }).joinToString("\n"),
                    ModDisplayMessage.MessageKind.INFORMATION,
                )
                else -> ShowConflicts(
                    command.conflicts.map { [element, conflict] ->
                        ShowConflicts.Conflict(
                            messages = conflict.messages.map { StringUtil.removeHtmlTags(it, true) },
                            location = element.getLspLocationForDefinition(),
                        )
                    },
                )
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
            // The server cannot change the options in memory, so a provider changes the settings files instead.
            is ModUpdateSystemOptions -> server
                ?.let { with(it) { LSSystemOptionsCommandProvider.createCommand(command, actionContext) } }
                ?.let { from(it, actionContext, server) }
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
            return Snippet(cmd.file.url.toFileUrl(), vars)
        }
    }
}

private fun ModUpdateFileText.toEdits(): List<ModCommandData.Edit> {
    if (this.updatedRanges.isEmpty()) {
        // The command has no fragments, so one edit replaces the whole text.
        return listOf(ModCommandData.Edit(0, oldText.length, newText))
    }
    var diff = 0
    return this.updatedRanges.map {
        val edit = ModCommandData.Edit(
            it.offset + diff, it.offset + diff + it.oldLength,
            this.newText.substring(it.offset, it.offset + it.newLength)
        )
        diff += it.oldLength - it.newLength
        edit
    }
}

/**
 * Executes [command] against [client], and returns `false` when the user stopped it. Only a
 * [ModCommandData.ShowConflicts] can answer `false`, and only a [ModCommandData.Composite] reads the answer: it
 * runs no further command, because the commands after the conflicts are the edits of the fix. This mirrors
 * [executeComposite][com.intellij.lang.impl.modcommand.ModCommandExecutorImpl] in the IDE.
 *
 * [changedFiles] holds the new text of each file this call already changed, because the document on disk is
 * behind until the client applies the edit. [sentVersions] holds the version the last edit of each file named.
 *
 * Every edit is sent with [applyEditOrRefuse], so an edit the client does not apply stops the command, and a
 * [ModCommandData.Composite] runs no further step. When the edit names an old version, the user gets a message and
 * the request fails with `ContentModified`; any other rejection fails it with `RequestFailed` and the client's
 * reason. Every [TextDocumentEdit][com.jetbrains.lsp.protocol.TextDocumentEdit] names the client version of the
 * text its offsets refer to ([versionedEdit]), and the client rejects an edit of another version.
 *
 * The steps of a [ModCommandData.Composite] are not checked again with stamps; the versions guard them. The
 * server handles a notification inline, as it reads it (`withLsp.kt` in `fleet/lsp.protocol`), and both clients send
 * `textDocument/didChange` before they answer `workspace/applyEdit`. So after the answer is awaited, the
 * server holds the new version and text of the client. The next edit takes that version only when that text is
 * the one in [changedFiles], so a change the user typed in between refuses the command. A client that answers
 * first still reports the version of the previous edit, so the next edit names it again, and the client rejects
 * it. That is a safe stop, never a wrong edit. [changedFiles] is still needed for a closed file: the server learns
 * its new text only from the file watcher, later.
 */
context(_: LSServer, _: LSAnalysisContext)
suspend fun executeCommand(
    command: ModCommandData,
    client: LspClient,
    changedFiles: MutableMap<String, String> = mutableMapOf(),
    sentVersions: MutableMap<String, Int> = mutableMapOf(),
): Boolean {
    suspend fun versionedEdit(fileUrl: String, sourceText: CharSequence, edits: List<TextEdit>): TextDocumentEdit =
        client.versionedEdit(fileUrl, sourceText, edits, sentVersions[fileUrl]).also { edit ->
            edit.textDocument.version?.let { sentVersions[fileUrl] = it }
        }

    fun findDocument(fileUrl: String): Document? =
        changedFiles[fileUrl]?.let { DocumentImpl(it) }
        ?: VirtualFileManager.getInstance().findFileByUrl(fileUrl)?.findDocument()
        ?: readDocumentFromDisk(fileUrl)
    when (command) {
        is ModCommandData.Nothing -> {}

        is ModCommandData.CreateFile -> {
            when (command.content) {
                is ModCommandData.CreateFile.Content.Text -> {
                    client.applyEditOrRefuse(
                        label = "Create ${command.fileUrl}",
                        edit = WorkspaceEdit(
                            documentChanges = listOf(
                                CreateFile(DocumentUri(command.fileUrl.intellijUriToLspUri())),
                                // The client has not opened a new file, so the edit names no version. An open one must be empty.
                                versionedEdit(command.fileUrl, "", listOf(TextEdit(Range.BEGINNING, command.content.text))),
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
            client.applyEditOrRefuse(
                label = "Delete ${command.fileUrl}",
                edit = WorkspaceEdit(
                    documentChanges = listOf(DeleteFile(DocumentUri(command.fileUrl.intellijUriToLspUri()))),
                ),
            )
        }

        is ModCommandData.MoveFile -> {
            client.applyEditOrRefuse(
                label = "Move ${command.fileUrl} to ${command.targetUrl}",
                edit = WorkspaceEdit(
                    documentChanges = listOf(
                        RenameFile(
                            DocumentUri(command.fileUrl.intellijUriToLspUri()), DocumentUri(command.targetUrl.intellijUriToLspUri())
                        ),
                    ),
                ),
            )
        }

        is ModCommandData.Snippet -> {
            val doc = findDocument(command.fileUrl)
            // A template without tab stops has nothing to start, and its text is already in the document
            // (`toTextEdit` derives its range from the tab stops, so it cannot even be built).
            if (doc != null && command.vars.isNotEmpty()) {
                client.applyEditOrRefuse(
                    label = "Run snippet in ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(
                            versionedEdit(command.fileUrl, doc.immutableCharSequence, listOf(command.toTextEdit(doc))),
                        ),
                    ),
                )
            }
        }

        is ModCommandData.UpdateFileText -> {
            // The edits refer to the text after the previous commands, which the document on disk does not have yet.
            val doc = findDocument(command.fileUrl)
            if (doc == null) {
                LOG.warn("No document to update: ${command.fileUrl}")
            }
            else {
                client.applyEditOrRefuse(
                    label = "Update ${command.fileUrl}",
                    edit = WorkspaceEdit(
                        documentChanges = listOf(
                            versionedEdit(command.fileUrl, doc.immutableCharSequence, command.edits.map { it.toTextEdit(doc) }),
                        ),
                    ),
                )
                changedFiles[command.fileUrl] = command.applyEdits(doc.text)
            }
        }

        is ModCommandData.Navigate -> {
            val selectionStart = command.selectionStart.takeIf { it != -1 } ?: command.caret
            val selectionEnd = command.selectionEnd.takeIf { it != -1 } ?: command.caret
            var selection: Range? = null
            if (selectionStart != -1 && selectionEnd != -1) {
                val doc = findDocument(command.fileUrl)
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

        is ModCommandData.Composite -> {
            val commands = command.commands
            // The client starts a snippet only in the active editor, and a new file has no editor yet.
            // The IDE opens the editor of a template itself, so a new file is shown before its first snippet.
            val createdNotShownFiles = mutableSetOf<String>()
            for ([index, nested] in commands.withIndex()) {
                when (nested) {
                    is ModCommandData.CreateFile -> createdNotShownFiles += nested.fileUrl
                    is ModCommandData.Navigate -> createdNotShownFiles -= nested.fileUrl
                    is ModCommandData.Snippet -> if (createdNotShownFiles.remove(nested.fileUrl)) {
                        val caret = nested.vars.minOfOrNull { it.start } ?: 0
                        executeCommand(ModCommandData.Navigate(nested.fileUrl, caret, caret, caret), client, changedFiles, sentVersions)
                    }
                    else -> {}
                }
                val proceed = when (nested) {
                    // The commands after the conflicts are the edits of the fix. `executeComposite` in the IDE
                    // passes the same sublist as the tail, and it is what the change guard has to watch.
                    is ModCommandData.ShowConflicts ->
                        confirmConflicts(nested, commands.subList(index + 1, commands.size), client)
                    else -> executeCommand(nested, client, changedFiles, sentVersions)
                }
                if (!proceed) return false
            }
        }

        is ModCommandData.ShowConflicts -> return confirmConflicts(command, emptyList(), client)

        is ModCommandData.DisplayMessage -> client.request(
            requestType = Window.ShowMessageRequest,
            params = ShowMessageRequestParams(
                type = when (command.messageKind) {
                    ModDisplayMessage.MessageKind.ERROR -> MessageType.Error
                    ModDisplayMessage.MessageKind.INFORMATION -> MessageType.Info
                },
                // A message can hold HTML, like `<b><code>test()</code></b>`, but the client shows plain text.
                message = command.message.htmlToPlainText(),
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
                sentVersions = sentVersions,
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
                    // An entry is a lazy action, which computes its edits when the user picks it, so it needs no stamps.
                    ChooseActionMenuEntry(entry.name, applyFixCommand(entry.action))
                },
            ),
        )

        // A lazy action is resolved by LSApplyFixCommandDescriptorProvider, before the analysis context this
        // function runs in is opened, so it never reaches here.
        is ModCommandData.LazyAction -> LOG.error("The lazy action $command was not resolved before execution")
    }
    return true
}

/**
 * The document of a local file that the virtual file system of the server misses, such as a file outside the index.
 * `null` when the file is not on the disk.
 */
private fun readDocumentFromDisk(fileUrl: String): Document? {
    if (!fileUrl.startsWith(StandardFileSystems.FILE_PROTOCOL_PREFIX)) return null
    val path = Path.of(VfsUtilCore.urlToPath(fileUrl))
    if (!Files.isRegularFile(path)) return null
    return try {
        DocumentImpl(StringUtil.convertLineSeparators(Files.readString(path)))
    }
    catch (e: IOException) {
        LOG.warn("Cannot read $path", e)
        null
    }
}

/**
 * [tail] holds the commands after the conflicts, which are the edits of the fix. They were computed against the
 * text of that moment. An edit the user makes while the question is open therefore makes the whole tail stale,
 * and applying it would put text at the wrong offset. Each edit names the document version, but a version
 * protects only an open document, and it stops a stale tail at its first rejected edit, maybe after other edits
 * already applied. So the [DocumentStamp] of every file the tail writes is taken before and after the question,
 * and any difference stops the fix before its first edit. A tail which holds a
 * [ModCommandData.ChooseAction] names no file, and every open document is watched for it. The IDE guards the
 * same thing with `ActionContextPointer.restoreAndCheck`, and shows the same kind of message.
 *
 * @see com.jetbrains.ls.kotlinLsp.requests.core.confirmConflicts
 */
context(server: LSServer)
private suspend fun confirmConflicts(
    command: ModCommandData.ShowConflicts,
    tail: List<ModCommandData>,
    client: LspClient,
): Boolean = confirmConflicts(command, client) { tail.watchedDocumentStamps() }

/**
 * Asks whether to continue despite the conflicts in [command].
 * Returns `true` when no conflicts exist, or the user chooses `Continue` and the watched documents stay unchanged.
 *
 * [watchedDocuments] takes a snapshot of the watched documents before and after the question.
 * Prepared edits can become stale if a watched document changes while the question is open.
 * In particular, [ModCommandData.UpdateFileText] carries a diff that the client applies without a document version.
 * Any difference between the snapshots stops the operation.
 *
 * Navigation stays free: `window/showDocument` and the reveal of a conflict change no text, so they change no snapshot.
 */
internal suspend fun <T> confirmConflicts(
    command: ModCommandData.ShowConflicts?,
    client: LspClient,
    watchedDocuments: suspend () -> T,
): Boolean {
    // `from` maps an empty command to `Nothing`, so this only guards a hand-built one.
    if (command == null || command.conflicts.isEmpty()) return true
    val before = watchedDocuments()
    if (!client.askToContinue(command)) return false

    val after = watchedDocuments()
    if (before != after) {
        LOG.info("The user confirmed the fix, but the documents changed: $before -> $after")
        client.refuseStaleDocument()
    }
    return true
}

/**
 * Shows the conflicts of [command] with [ShowConflictsRequest] and returns `true` for a `Continue` answer.
 *
 * [ModCommandData.from] converts a [ModShowConflicts] only for a client which declares `intellijExtensions`,
 * so this needs no fallback. LSP has no standard request for a list of locations with a decision.
 */
private suspend fun LspClient.askToContinue(command: ModCommandData.ShowConflicts): Boolean {
    val result = request(
        requestType = ShowConflictsRequest,
        params = ShowConflictsParams(
            title = LspServerBundle.message("conflicts.title"),
            conflicts = command.conflicts,
            continueLabel = LspServerBundle.message("conflicts.action.continue"),
            cancelLabel = LspServerBundle.message("conflicts.action.cancel"),
            revealLabel = LspServerBundle.message("conflicts.action.reveal"),
            documentChangedLabel = LspServerBundle.message("error.document.changed"),
        ),
    )
    // An exhaustive `when` keeps both answers named, so a third one cannot pass unnoticed.
    return when (result.decision) {
        ShowConflictsDecision.CONTINUE -> true
        ShowConflictsDecision.CANCEL -> false
    }
}

/**
 * The [DocumentStamp] of each file this guard watches for these commands. A file that does not exist yet, such
 * as the target of a [ModCommandData.CreateFile], has no stamp, and `null` stands for that.
 *
 * The stamps are taken in a new analysis context, because the context of the caller is a snapshot that does not
 * see a later `textDocument/didChange`. An open document is stamped with the text the client sent; a closed one
 * with the text on disk, so a `textDocument/didClose` of unsaved edits counts as a change.
 */
context(server: LSServer)
private suspend fun List<ModCommandData>.watchedDocumentStamps(): DocumentStamps {
    val written = asSequence().flatMap { it.writtenFileUrls() }
    // A ModCommandData.ChooseAction names no file, because it builds its edits only after the user picks one.
    // Every open document is watched instead. That is the only safe superset, and it does make an edit in an
    // unrelated open file stop the fix.
    val watched = when {
        any { it.choosesLater() } -> written + server.documents.openDocuments().asSequence().mapNotNull { it.lspUriToIntellijUri() }
        else -> written
    }
    val fileUrls = watched.distinct().toList()
    if (fileUrls.isEmpty()) return emptyMap()
    return server.withAnalysisContext { readAction { stampsOf(fileUrls) } }
}

/** Whether [this] holds a [ModCommandData.ChooseAction], whose files no caller can know yet. */
private fun ModCommandData.choosesLater(): Boolean = when (this) {
    is ModCommandData.ChooseAction -> true
    is ModCommandData.Composite -> commands.any { it.choosesLater() }
    else -> false
}

/** The urls of the files [this] writes to, including the ones of a nested [ModCommandData.Composite]. */
internal fun ModCommandData.writtenFileUrls(): Sequence<String> = when (this) {
    is ModCommandData.Composite -> commands.asSequence().flatMap { it.writtenFileUrls() }
    is ModCommandData.CreateFile -> sequenceOf(fileUrl)
    is ModCommandData.DeleteFile -> sequenceOf(fileUrl)
    is ModCommandData.MoveFile -> sequenceOf(fileUrl, targetUrl)
    is ModCommandData.Snippet -> sequenceOf(fileUrl)
    is ModCommandData.UpdateFileText -> sequenceOf(fileUrl)
    // These write no text of their own. A ChooseAction names no file either, and [choosesLater] answers for it.
    // A LazyAction builds its edits in a later round trip, which this guard cannot see.
    is ModCommandData.ChooseAction,
    is ModCommandData.CopyToClipboard,
    is ModCommandData.DisplayMessage,
    is ModCommandData.LaunchEditorAction,
    is ModCommandData.LazyAction,
    is ModCommandData.Navigate,
    is ModCommandData.Nothing,
    is ModCommandData.ShowConflicts,
    is ModCommandData.StartRename -> emptySequence()
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
 * The caller checks `runEditorCommand` before attaching the command to a completion item or sending it in a notification.
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

/**
 * The conflicts to show, and the text to show them with. The server sends every user-visible string, because a
 * localized string of this server belongs in its `LspServerBundle`, not in the client.
 */
@Serializable
data class ShowConflictsParams(
    val title: String,
    val conflicts: List<ModCommandData.ShowConflicts.Conflict>,
    val continueLabel: String,
    val cancelLabel: String,
    val revealLabel: String,
    val documentChangedLabel: String,
)

/** The answer of the user to a [ShowConflictsRequest]. */
@Serializable
enum class ShowConflictsDecision {
    @SerialName("continue") CONTINUE,
    @SerialName("cancel") CANCEL,
}

@Serializable
data class ShowConflictsResult(val decision: ShowConflictsDecision)

/**
 * A custom server -> client request (used by the ModCommand [ModShowConflicts]) which asks the user to confirm a
 * fix that reports conflicts. The client shows each [ModCommandData.ShowConflicts.Conflict] and can open its
 * location, then answers [ShowConflictsDecision].
 *
 * This is a request, not a notification, because the server holds the edits of the fix and applies them only
 * after the answer. `confirmConflicts` sends it only to a client which declares `intellijExtensions`. Such a
 * client which still does not handle it answers an error, the request fails, and the fix stops. No edit applies.
 */
val ShowConflictsRequest: RequestType<ShowConflictsParams, ShowConflictsResult, Unit> =
    RequestType(
        "intellij/showConflicts",
        ShowConflictsParams.serializer(),
        ShowConflictsResult.serializer(),
        Unit.serializer(),
    )
