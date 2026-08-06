// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModRegisterTabOut
import com.intellij.modcommand.ModStartRename
import com.intellij.modcommand.ModStartTemplate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.containers.addIfNotNull
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData.Snippet
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData.SnippetVar
import com.jetbrains.lsp.protocol.InsertTextFormat
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit

object ModCompletionCommandConverter {
  data class Edits(
    val main: TextEdit,
    val edits: List<TextEdit>,
    val format: InsertTextFormat,
    val rest: ModCommand,
  )

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
}
