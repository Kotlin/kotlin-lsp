// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModDisplayMessage
import com.intellij.modcommand.ModLaunchEditorAction
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModNothing
import com.intellij.modcommand.ModRegisterTabOut
import com.intellij.modcommand.ModStartRename
import com.intellij.modcommand.ModStartTemplate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.containers.addIfNotNull
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.impl.common.modcommands.CHOICE_SEPARATOR
import com.jetbrains.ls.api.features.impl.common.modcommands.flattenChoices
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData.Snippet
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData.SnippetVar
import com.jetbrains.ls.kotlinLsp.requests.core.RENAME_EDITOR_COMMAND
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.InsertTextFormat
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import kotlinx.serialization.json.encodeToJsonElement

object ModCompletionCommandConverter {
  data class Edits(
    val main: TextEdit,
    val edits: List<TextEdit>,
    val format: InsertTextFormat,
    val rest: ModCommand,
  )

  /**
   * What a completion item applies once its [ModChooseAction]s are dealt with: the [command] to apply, the
   * [labelSuffix] naming the choice it came from (`null` if the item carries no choice of its own), and whether
   * the command has to be [deferredEntirely] to the apply-time LSP command instead of becoming text edits now.
   */
  data class UnwrappedCommand(val command: ModCommand, val labelSuffix: String?, val deferredEntirely: Boolean = false)

  private data class MyEdit(val from: Int, val to: Int, val newText: String) {
    fun toTextEdit(document: Document): TextEdit =
      TextEdit(Range(document.positionByOffset(from), document.positionByOffset(to)), newText)
  }

  private data class MainEdit(
    val from: Int,
    val to: Int,
    val snippetText: String,
    val snippet: Snippet,
    val diff: Int,
  ) {
    fun toMyEdit() = MyEdit(from, to, snippet.toString(snippetText))

    fun convertNavigation(document: Document, cmd: ModNavigate): MainEdit? {
      val delta = from - diff
      val pos = cmd.caret - delta
      val newText = snippetText
      if (pos > newText.length) {
        val lenDiff = pos - newText.length
        val offset = lenDiff + to - diff
        if (offset <= document.textLength && document.getLineNumber(offset) == document.getLineNumber(to)) {
          val suffix = document.getText(TextRange(to - diff, to - diff + lenDiff))
          return copy(to = to + lenDiff, snippetText = newText + suffix).convertNavigation(document, cmd)
        }
      }
      if (pos in 0..newText.length) {
        if (cmd.selectionStart != cmd.selectionEnd) {
          val selStart = cmd.selectionStart - delta
          val selEnd = cmd.selectionEnd - delta
          if (selStart in 0..newText.length && selEnd in 0..newText.length) {
            return copy(snippet = snippet.add(SnippetVar(selStart, selEnd, 1), SnippetVar(selEnd, selEnd, 0)))
          }
        }
        return copy(snippet = snippet.add(SnippetVar(pos, pos, 0)))
      }
      return null
    }

    fun convertTemplate(cmd: ModStartTemplate): MainEdit? {
      val vars = mutableListOf<SnippetVar>()
      val delta = from - diff
      val newText = snippetText
      val map = mutableMapOf<String, Int>()
      var i = 0
      for (field in cmd.fields) {
        when (field) {
          is ModStartTemplate.EndField -> {
            val pos = field.range.startOffset - delta
            if (pos !in 0..newText.length) return null
            vars.add(SnippetVar(pos, pos, 0))
          }
          is ModStartTemplate.ExpressionField -> {
            val start = field.range.startOffset - delta
            val end = field.range.endOffset - delta
            if (start !in 0..newText.length || end !in 0..newText.length) return null
            val varName = field.varName
            val num = if (varName == null) ++i else map.computeIfAbsent(varName) { ++i }
            vars.add(SnippetVar(start, end, num))
          }
          is ModStartTemplate.DependantVariableField -> {
            // Process dependent fields after all expression fields are known.
          }
        }
      }
      for (field in cmd.fields) {
        if (field !is ModStartTemplate.DependantVariableField) continue
        val start = field.range.startOffset - delta
        val end = field.range.endOffset - delta
        if (start !in 0..newText.length || end !in 0..newText.length) return null
        val sourceNum = map[field.dependantVariableName]
        val sourceField = cmd.fields.asSequence()
          .filterIsInstance<ModStartTemplate.ExpressionField>()
          .firstOrNull { it.varName == field.dependantVariableName }
        val isMirror = sourceNum != null && sourceField != null && run {
          val srcStart = sourceField.range.startOffset - delta
          val srcEnd = sourceField.range.endOffset - delta
          srcStart in 0..newText.length && srcEnd in 0..newText.length &&
          newText.substring(srcStart, srcEnd) == newText.substring(start, end)
        }
        val num = if (isMirror) sourceNum else map.computeIfAbsent(field.varName) { ++i }
        vars.add(SnippetVar(start, end, num))
      }
      return copy(snippet = Snippet("", vars))
    }
  }

  /**
   * The items to offer for [originalCommand], which is what accepting one completion candidate performs.
   *
   * A [ModChooseAction] means the candidate cannot be applied before the user says which variant they meant.
   * Clients declaring `intellijExtensions` show that chooser themselves, exactly like the IDE does, so a single
   * item is offered and its whole command is deferred to the apply step. Generic LSP clients have no chooser
   * primitive, so the choices are expanded up front into one item per variant — at most [maxChoicesPerItem] of
   * them, since they compete for the slots of the whole completion list — each labelled with its own name.
   *
   * [actionContext] describes the document as it is now, [deferredContext] as it will be once the item's own edit
   * was applied; see [deferred] for why the two differ.
   */
  context(server: LSServer?)
  fun unwrapChoices(
    originalCommand: ModCommand,
    actionContext: ActionContext,
    deferredContext: ActionContext,
    maxChoicesPerItem: Int,
  ): List<UnwrappedCommand> {
    // Nothing is meaningless.
    if (originalCommand is ModNothing) return listOf()
    // Error messages usually indicate that something is impossible.
    if (originalCommand is ModDisplayMessage && originalCommand.kind == ModDisplayMessage.MessageKind.ERROR) return listOf()
    val unpacked = originalCommand.unpack()
    val chooseAction = unpacked.firstOrNull() as? ModChooseAction
                       ?: return listOf(UnwrappedCommand(originalCommand, null))

    if (chooseAction.actions().isEmpty()) {
      return listOf(UnwrappedCommand(originalCommand, null))
    }

    if (canDeferChoiceToClient(chooseAction, unpacked, deferredContext)) {
      return listOf(UnwrappedCommand(originalCommand, null, deferredEntirely = true))
    }

    val baseCommand = unpacked.drop(1).fold(ModCommand.nop(), ModCommand::andThen)

    val choices = chooseAction.actions()
    val shownChoices =
      if (choices.size <= maxChoicesPerItem) chooseAction
      else ModCommand.chooseAction(chooseAction.title(), choices.take(maxChoicesPerItem))

    return shownChoices.flattenChoices(actionContext, maxChoicesPerItem)
      .orEmpty()
      .map { (choiceNames, command) ->
        UnwrappedCommand(
          command = command.andThen(baseCommand),
          labelSuffix = choiceNames.joinToString(CHOICE_SEPARATOR).ifEmpty { null },
        )
      }
  }

  /**
   * Whether [chooseAction] can be left to the client to show instead of being expanded up front.
   *
   * Besides the client declaring `intellijExtensions`, the chooser has to be the whole command: anything next to
   * it in [unpacked] would be applied by the client on insertion, while the choices are performed only once the
   * user picks, against the document as it is at that moment.
   *
   * At least two choices have to be pickable, too. `ModCommandData.from` collapses a single-choice chooser and
   * performs it right away, and the command that comes out of it is better off going through [split], which
   * inserts it as a snippet, than being deferred to the apply step.
   */
  context(server: LSServer?)
  private fun canDeferChoiceToClient(
    chooseAction: ModChooseAction,
    unpacked: List<ModCommand>,
    actionContext: ActionContext,
  ): Boolean {
    if (server?.config?.clientSupportsIntellijExtensions != true) return false
    if (unpacked.size != 1) return false
    val pickableChoices = chooseAction.actions().count { action ->
      runCatching { action.getPresentation(actionContext) }.getOrNull() != null
    }
    return pickableChoices >= 2
  }

  fun split(file: PsiFile, start: Int, caret: Int, command: ModCommand): Edits {
    val unprocessed = mutableListOf<ModCommand>()
    var movedCaret = start
    val document = file.fileDocument
    val fragments = mutableListOf<MyEdit>()
    var removePrefix: MyEdit? = if (start < caret) MyEdit(start, caret, "") else null
    var mainEdit: MainEdit? = null
    val virtualFile = file.virtualFile
    for (cmd in command.unpack()) {
      if (cmd is ModUpdateFileText && cmd.file == virtualFile) {
        val shrunkCommand = cmd.shrinkFragments()
        val newText = shrunkCommand.newText
        var diff = 0
        for (fragment in shrunkCommand.updatedRanges) {
          val string = newText.substring(fragment.offset, fragment.offset + fragment.newLength)
          val from = fragment.offset + diff
          var to = fragment.offset + fragment.oldLength + diff
          var newDiff = diff
          if (removePrefix != null && removePrefix.from in from..to) {
            to += caret - start
            newDiff += caret - start
            removePrefix = null
          }
          val edit = MyEdit(from, to, string)
          when {
            movedCaret in from..to && document.getLineNumber(from) == document.getLineNumber(to) -> {
              mainEdit = MainEdit(from, to, string, Snippet(""), diff)
            }
            start in from..<caret && caret < to -> {
              // A multi-line replacement containing the prefix must become a non-overlapping main edit.
              val mainText = if (from < start) {
                fragments.add(MyEdit(from, start, string))
                ""
              }
              else {
                string
              }
              fragments.add(MyEdit(caret, to, ""))
              mainEdit = MainEdit(start, caret, mainText, Snippet(""), diff)
            }
            else -> fragments.add(edit)
          }
          diff = newDiff + fragment.oldLength - fragment.newLength
        }
        movedCaret = shrunkCommand.translateOffset(movedCaret, true)
        continue
      }
      if (cmd is ModStartTemplate && cmd.file == virtualFile) {
        val updatedMainEdit = mainEdit?.convertTemplate(cmd)
        if (updatedMainEdit != null) {
          mainEdit = updatedMainEdit
          continue
        }
      }
      if (cmd is ModNavigate && cmd.file == virtualFile) {
        if (cmd.caret == movedCaret && cmd.selectionStart == cmd.selectionEnd) continue
        val updatedMainEdit = mainEdit?.convertNavigation(document, cmd)
        if (updatedMainEdit != null) {
          mainEdit = updatedMainEdit
          continue
        }
      }
      // Tab-out is not supported in LSP (yet).
      if (cmd is ModRegisterTabOut) continue
      if (cmd is ModStartRename && cmd.file == virtualFile && mainEdit != null && mainEdit.snippet.vars.none { it.name == 0 }) {
        val nameRange = cmd.symbolRange().nameIdentifierRange()
        val currentMainEdit = mainEdit
        if (nameRange != null) {
          val delta = currentMainEdit.from - currentMainEdit.diff
          val pos = nameRange.startOffset - delta
          if (pos in 0..currentMainEdit.snippetText.length) {
            mainEdit = currentMainEdit.copy(snippet = currentMainEdit.snippet.add(SnippetVar(pos, pos, 0)))
          }
        }
        unprocessed.add(cmd)
        continue
      }
      unprocessed.add(cmd)
    }
    fragments.addIfNotNull(removePrefix)
    val position = document.positionByOffset(caret)
    val main = mainEdit?.toMyEdit()?.toTextEdit(document) ?: TextEdit(Range(position, position), "")
    val edits = fragments.map { it.toTextEdit(document) }.toMutableList()
    val format = if (mainEdit == null) InsertTextFormat.PlainText else InsertTextFormat.Snippet
    return Edits(main, edits, format, unprocessed.fold(ModCommand.nop(), ModCommand::andThen))
  }

  /**
   * The [Edits] of an item that inserts nothing of its own when accepted and leaves its whole [command] to the
   * apply-time LSP command.
   *
   * Used for a chooser the client shows itself: what the item inserts is only known once the user has picked, and
   * the choices are performed against the document as it is then. That document still has to be the one those
   * choices were computed against, which is the file *without* the typed prefix — performing a completion item
   * assumes the insertion removed it, which is why [split] starts from a `removePrefix` edit. Nothing else deletes
   * it here, so the main edit does.
   */
  fun deferred(document: Document, prefixStart: Int, caret: Int, command: ModCommand): Edits {
    val range = Range(document.positionByOffset(prefixStart), document.positionByOffset(caret))
    return Edits(
      main = TextEdit(range, ""),
      edits = emptyList(),
      format = InsertTextFormat.PlainText,
      rest = command,
    )
  }

  /**
   * Builds the LSP [Command] the client runs after applying a completion item whose [edits] came out of [split] or
   * [deferred]; [applyCommandName] is the client-side command that applies a [ModCommandData], since its name is
   * registered per language.
   */
  context(server: LSServer?)
  fun toCommand(edits: Edits, actionContext: ActionContext, applyCommandName: String): Command? {
    val rest = edits.rest
    if (rest.isEmpty) return null
    // A rename carries no competing navigation/template (see `PsiUpdateImpl#getCommand`), so a `${0}` in the main
    // snippet means the rename placeholder was placed inside the main edit and the caret already lands on the
    // symbol — plain `editor.action.rename` renames it at the caret, with no navigation needed.
    // Otherwise the rename target lives outside the main edit (e.g. the field declaration created by the "field"
    // postfix), and the generic path below turns it into a [ModCommandData.StartRename], which navigates first.
    if (rest is ModStartRename && edits.format == InsertTextFormat.Snippet && edits.main.newText.contains($$"${0}")) {
      // `editor.action.rename` is a client-side command, so only a client declaring `intellijExtensions` is known
      // to have it. For the others there is nothing left to do: no rename can be started, and the `${0}` has
      // already put the caret on the symbol, so a navigation would only fight the snippet session.
      return when {
        server?.config?.clientSupportsIntellijExtensions == true ->
          Command(LspServerBundle.message("command.rename"), RENAME_EDITOR_COMMAND)
        else -> null
      }
    }
    if (rest is ModLaunchEditorAction) {
      // `editor.action.triggerSuggest` and `editor.action.triggerParameterHints` are VSCode built-ins, not LSP: a
      // client that does not have them would bounce them back as a `workspace/executeCommand` the server cannot
      // handle. Only a client declaring `intellijExtensions` is known to be VSCode-based, so gate on that.
      if (server?.config?.clientSupportsIntellijExtensions == true) {
        when (rest.actionId) {
          ModLaunchEditorAction.ACTION_CODE_COMPLETION ->
            return Command(LspServerBundle.message("command.completion"), "editor.action.triggerSuggest")
          ModLaunchEditorAction.ACTION_PARAMETER_INFO ->
            return Command(LspServerBundle.message("command.parameter.info"), "editor.action.triggerParameterHints")
        }
      }
      if (rest.optional) return null
    }
    val data = ModCommandData.from(rest, actionContext, server)
               ?: ModCommandData.DisplayMessage("Unsupported command", ModDisplayMessage.MessageKind.ERROR)
    return Command(
      title = LspServerBundle.message("command.apply.completion"),
      command = applyCommandName,
      arguments = listOf(LSP.json.encodeToJsonElement<ModCommandData>(data)),
    )
  }
}
