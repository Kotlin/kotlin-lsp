// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.LightModCompletionServiceImpl
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcompletion.ModCompletionItem
import com.intellij.modcompletion.ModCompletionItemProvider
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.platform.diagnostic.telemetry.helpers.use
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.StubTextInconsistencyException
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSInjectedFile
import com.jetbrains.ls.api.core.features.lsFindInjectedFileAt
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.completion.LSCompletion
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.impl.common.modcommands.LSApplyFixCommandDescriptorProvider
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionItemLabelDetails
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams
import com.jetbrains.lsp.protocol.InsertTextFormat
import com.jetbrains.lsp.protocol.InsertTextMode
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import io.opentelemetry.api.trace.Tracer
import org.jetbrains.annotations.ApiStatus

/**
 * Generates LSP completion items using IntelliJ's ModCompletion infrastructure, for a host file of any language and
 * for an injection whose language has ModCompletion providers of its own.
 *
 * Converts [ModCommand]-based completions to LSP text edits, transforming caret navigation into snippet placeholders.
 * The part of a command that cannot be expressed as text edits becomes an LSP command named by `applyCommandName`;
 * by default it is [LSApplyFixCommandDescriptorProvider.commandDescriptor], registered for every language.
 *
 * Returns at most [MAX_TO_SEND] items.
 */
@ApiStatus.Internal
object LSModCompletion {
    private val tracer: Tracer = TelemetryManager.getTracer(LSCompletion.scope)

    private const val MAX_TO_SEND = 25

    /**
     * How many variants of one candidate's [ModChooseAction] are offered as separate items to a client that cannot
     * show a chooser itself. Kept small on purpose: the variants compete for the [MAX_TO_SEND] slots of the whole
     * completion list, and each of them has to be performed to know what it inserts.
     */
    private const val MAX_CHOICES_PER_ITEM = 4
    private const val SPAN_GET_ITEMS = "completion.mod.getItems"
    private const val SPAN_TO_LSP_ITEMS = "completion.mod.toLspItems"
    private val snippetEscapeCharacters = Regex("""[\\}$]""")
    private const val SNIPPET_REPLACEMENT = $$"\\\\$0"

    /**
     * The ModCompletion items at [position] of [psiFile].
     *
     * The server is only needed for the part of a [ModCommand] that cannot be expressed as text edits, so it is
     * optional.
     */
    context(server: LSServer?)
    suspend fun provideCompletionForDocument(
        psiFile: PsiFile,
        position: Position,
        applyCommandName: String = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    ): CompletionList {
        return readAction {
            if (!psiFile.isValid) return@readAction CompletionList(isIncomplete = false, items = emptyList())
            completeInReadAction(psiFile, psiFile.fileDocument.offsetByPosition(position), applyCommandName)
        }
    }

    /**
     * Completes at a caret inside a language injection of the document of [params] through the ModCompletion providers
     * of the injected language. Null when the caret is not in an injection, or the injected language has no ModCompletion
     * providers of its own ([hasOwnModCompletionProviders]): the caller completes through its own route then.
     */
    context(server: LSServer)
    suspend fun provideInjectedCompletion(
        params: CompletionParams,
        applyCommandName: String = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    ): CompletionList? {
        if (!params.textDocument.isSource()) return null
        return server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                val hostOffset = psiFile.fileDocument.offsetByPosition(params.position)
                val (injected, injectedOffset) = lsFindInjectedFileAt(psiFile, hostOffset) ?: return@readAction null
                if (!hasOwnModCompletionProviders(injected.psiFile.language)) return@readAction null
                completeInjectedInReadAction(psiFile, hostOffset, injected, injectedOffset, applyCommandName)
            }
        }
    }

    /** [provideCompletionForDocument] at [offset] of [psiFile]; requires read access. */
    context(server: LSServer?)
    fun completeInReadAction(
        psiFile: PsiFile,
        offset: Int,
        applyCommandName: String = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    ): CompletionList {
        val site = CompletionSite(psiFile, LightModCompletionServiceImpl.computePrefixStart(psiFile, offset), offset)
        return modCompletionList(host = site, items = site, applyCommandName = applyCommandName)
    }

    /**
     * Completes at [injectedOffset] of an [injected] file of [hostFile] (LSP-1991) through the ModCompletion providers of the
     * injected language: they compute the items on the injected file, and each item is performed with the context of the
     * host, so its command edits the host document like the item of a host completion does. Requires read access.
     */
    context(server: LSServer?)
    fun completeInjectedInReadAction(
        hostFile: PsiFile,
        hostOffset: Int,
        injected: LSInjectedFile,
        injectedOffset: Int,
        applyCommandName: String = LSApplyFixCommandDescriptorProvider.commandDescriptor.name,
    ): CompletionList {
        val (length, lengthInFragment) = injectedPrefixLength(injected, hostOffset, injectedOffset)
        val prefixStart = injectedOffset - length
        return modCompletionList(
            host = CompletionSite(hostFile, hostOffset - lengthInFragment, hostOffset),
            items = CompletionSite(injected.psiFile, prefixStart, injectedOffset),
            prefixBeforeFragment = injected.documentWindow.charsSequence.substring(prefixStart, injectedOffset - lengthInFragment),
            applyCommandName = applyCommandName,
        )
    }

    /**
     * Whether [language] or one of its base languages has ModCompletion providers registered for it, not only the ones registered for
     * any language. An injected language without them completes through the legacy helper of the host language's provider.
     * Reads the registrations: the list difference `forLanguage(language) - forLanguage(Language.ANY)` kept the `any` providers
     * for RegExp in the LS, which sent RegExp to ModCompletion with no items. Meta-language registrations are not counted.
     */
    fun hasOwnModCompletionProviders(language: Language): Boolean {
        val languageIds = generateSequence(language) { it.baseLanguage }.mapTo(HashSet()) { it.id }
        return ModCompletionItemProvider.EP_NAME.point?.extensionList.orEmpty().any { it.key in languageIds }
    }

    /**
     * The length of the prefix typed before [injectedOffset] in [injected], and the length of its part in the fragment that contains
     * [hostOffset]. As [LightModCompletionServiceImpl.computePrefixStart] does in a host, the prefix is the identifier before the caret
     * and the reference text before it. The identifier is found in the unescaped text: the injected PSI keeps the host text, where the `n`
     * of an escaped `\n` reads as a part of the prefix. The reference is found in the PSI, at the offset of the identifier in the PSI:
     * the escapes and the text block indents before the caret shift it from the unescaped one. Only the part in the fragment is edited
     * in the host, so the edit of an item never spans the host text between two fragments, like a `+` and a comment.
     */
    private fun injectedPrefixLength(injected: LSInjectedFile, hostOffset: Int, injectedOffset: Int): Pair<Int, Int> {
        val psiFile = injected.psiFile
        val manager = InjectedLanguageManager.getInstance(psiFile.project)
        val text = manager.getUnescapedText(psiFile)
        var unescapedStart = manager.mapInjectedOffsetToUnescaped(psiFile, injectedOffset)
        while (unescapedStart > 0 && StringUtil.isJavaIdentifierPart(text[unescapedStart - 1])) unescapedStart--
        var start = manager.mapUnescapedOffsetToInjected(psiFile, unescapedStart)
        start -= psiFile.findElementAt(start)?.let { CompletionUtil.findReferencePrefix(it, start) }?.length ?: 0
        val fragment = injected.documentWindow.hostRanges.firstOrNull { it.startOffset <= hostOffset && hostOffset <= it.endOffset }
        val length = injectedOffset - start
        return length to minOf(length, hostOffset - (fragment?.startOffset ?: hostOffset))
    }

    /** A [file] where completion runs, with the typed prefix from [prefixStart] to [caret]. */
    private class CompletionSite(val file: PsiFile, val prefixStart: Int, val caret: Int)

    /**
     * The ModCompletion items of [items], performed and converted to LSP edits against [host]. [items] is [host] itself, or the
     * injected file of a caret inside an injection in [host]; requires read access. [prefixBeforeFragment] is the part of the
     * injected prefix that lies in fragments before the one of the caret: the host prefix of [host] does not include it.
     * [applyCommandName] names the LSP command that applies the part of an item that is not a text edit.
     */
    context(server: LSServer?)
    private fun modCompletionList(
        host: CompletionSite,
        items: CompletionSite,
        prefixBeforeFragment: String = "",
        applyCommandName: String,
    ): CompletionList {
        val psiFile = host.file
        val document = psiFile.fileDocument
        val project = psiFile.project
        val position = host.caret
        val prefixStart = host.prefixStart
        val actionContext = ActionContext(project, psiFile, position, TextRange.create(prefixStart, position), null)
        // The document a deferred command will be performed against: the item's own edit has removed the typed
        // prefix by then, so the caret sits where the prefix started.
        val deferredContext = actionContext.withOffset(prefixStart).withSelection(TextRange.create(prefixStart, prefixStart))

        StubTextInconsistencyException.checkStubTextConsistency(psiFile)

        val mList = tracer.spanBuilder(SPAN_GET_ITEMS).use { span ->
            val mItems = buildList {
                LightModCompletionServiceImpl.getItems(
                    /* file = */ items.file,
                    /* startOffset = */ items.prefixStart,
                    /* caretOffset = */ items.caret,
                    /* invocationCount = */ 1,
                    /* type = */ CompletionType.BASIC,
                ) { item -> add(item) }
            }
            span.setAttribute("completion.candidates.count", mItems.size.toLong())
            mItems
        }

        val sentCount = minOf(MAX_TO_SEND, mList.size)
        val list = tracer.spanBuilder(SPAN_TO_LSP_ITEMS)
            .setAttribute("completion.candidates.count", mList.size.toLong())
            .setAttribute("completion.sent.count", sentCount.toLong())
            .setAttribute("completion.sent.limit", MAX_TO_SEND.toLong())
            .use {
                data class EditsWithMeta(
                    val index: Int,
                    val item: ModCompletionItem,
                    val edits: ModCompletionCommandConverter.Edits,
                    val command: Command?,
                    val labelSuffix: String?,
                )

                val allEdits = mList.subList(0, sentCount)
                    .flatMapIndexed { i, item: ModCompletionItem ->
                        val originalCommand = item.perform(actionContext, ModCompletionItem.DEFAULT_INSERTION_CONTEXT)
                        ModCompletionCommandConverter
                            .unwrapChoices(originalCommand, actionContext, deferredContext, MAX_CHOICES_PER_ITEM)
                            .mapNotNull { (command, labelSuffix, deferredEntirely) ->
                                val edits =
                                    (if (deferredEntirely) ModCompletionCommandConverter.deferred(document, prefixStart, position, command)
                                     else ModCompletionCommandConverter.split(psiFile, prefixStart, position, command))
                                        .keepingPrefixBeforeFragment(document, prefixStart, prefixBeforeFragment)
                                    ?: return@mapNotNull null
                                // A deferred command is converted now but performed only after the item was applied,
                                // so the context it is converted with — the one the chooser session stores — has to
                                // be the one describing the document at that point.
                                val commandContext = if (deferredEntirely) deferredContext else actionContext
                                val applyCommand = ModCompletionCommandConverter.toCommand(
                                    edits = edits,
                                    actionContext = commandContext,
                                    applyCommandName = applyCommandName,
                                )
                                EditsWithMeta(i, item, edits, applyCommand, labelSuffix)
                            }
                    }.take(MAX_TO_SEND)

                val minStartOffset = allEdits.minOfOrNull { document.offsetByPosition(it.edits.main.range.start) } ?: prefixStart
                val minStartPosition = document.positionByOffset(minStartOffset)

                allEdits.map { (i, item, splitResult, command, labelSuffix) ->
                    val presentation = item.presentation()
                    var detail = presentation.mainText().toText()
                    var label = item.mainLookupString()
                    if (detail.startsWith(label)) {
                        @Suppress("HardCodedStringLiteral")
                        detail = detail.substring(label.length)
                    } else {
                        label = detail
                        detail = ""
                    }
                    val itemLabel = if (labelSuffix != null) "$label ($labelSuffix)" else label
                    val adjustedMain = normalizeTextEditStart(document, splitResult.main, splitResult.format, minStartOffset, minStartPosition)
                    CompletionItem(
                        label = itemLabel,
                        sortText = getSortedFieldByIndex(i),
                        labelDetails = CompletionItemLabelDetails(
                            detail = detail,
                            description = presentation.detailText().toText(),
                        ),
                        kind = (item.contextObject() as? PsiElement)?.let { LSCompletionItemKindProvider.getKind(it) },
                        filterText = computeFilterText(document, prefixStart, item, adjustedMain).removePrefix(prefixBeforeFragment),
                        insertText = "",
                        insertTextMode = InsertTextMode.AsIs,
                        insertTextFormat = splitResult.format,
                        textEdit = CompletionItem.Edit.Text(adjustedMain),
                        additionalTextEdits = splitResult.edits.ifEmpty { null },
                        command = command,
                    )
                }
            }
        StubTextInconsistencyException.checkStubTextConsistency(psiFile)
        return CompletionList(isIncomplete = (mList.size > list.size), items = list)
    }

    /**
     * These edits with [prefixBeforeFragment] cut from the start of the main edit, so the item inserts only what the earlier fragments
     * do not hold; in a snippet, the prefix is cut as the snippet escapes it, like the `$` of a Java name. Null when the main edit does
     * not start with it at [prefixStart], like a deferred item or a keyword that gets a space before it: the item can be applied only by
     * also editing the earlier fragments, which its command was not performed against.
     */
    private fun ModCompletionCommandConverter.Edits.keepingPrefixBeforeFragment(
        document: Document,
        prefixStart: Int,
        prefixBeforeFragment: String,
    ): ModCompletionCommandConverter.Edits? {
        if (prefixBeforeFragment.isEmpty()) return this
        val prefix =
            if (format == InsertTextFormat.Snippet) prefixBeforeFragment.replace(snippetEscapeCharacters, SNIPPET_REPLACEMENT)
            else prefixBeforeFragment
        if (document.offsetByPosition(main.range.start) != prefixStart || !main.newText.startsWith(prefix)) return null
        return copy(main = main.copy(newText = main.newText.substring(prefix.length)))
    }

    private fun computeFilterText(document: Document, prefixStart: Int, item: ModCompletionItem, main: TextEdit): String {
        val editStartOffset = document.offsetByPosition(main.range.start)
        return if (editStartOffset < prefixStart) {
            val expressionWithDot = document.charsSequence.substring(editStartOffset, prefixStart)
            val lookupString = item.mainLookupString()
            if (expressionWithDot.endsWith(".") && lookupString.startsWith(".")) {
                expressionWithDot + lookupString.substring(1)
            } else {
                expressionWithDot + lookupString
            }
        } else {
            item.mainLookupString()
        }
    }

    /**
     * Adjusts the [main] text edit so that its range starts at [minStartOffset].
     * If the edit already starts at or before [minStartOffset], it is returned unchanged.
     * Otherwise, the document text between [minStartOffset] and the original start
     * is prepended to the edit's newText (with snippet-escaping when needed).
     *
     * This ensures all completion items share the same textEdit range start,
     * which prevents VS Code from assigning different scores
     * and thus ignoring [CompletionItem.sortText].
     */
    private fun normalizeTextEditStart(
        document: Document,
        main: TextEdit,
        format: InsertTextFormat,
        minStartOffset: Int,
        minStartPosition: Position,
    ): TextEdit {
        val editStartOffset = document.offsetByPosition(main.range.start)
        if (editStartOffset <= minStartOffset) return main

        val prependText = document.charsSequence.substring(minStartOffset, editStartOffset)
        val escapedPrepend = if (format == InsertTextFormat.Snippet) {
            prependText.replace(snippetEscapeCharacters, SNIPPET_REPLACEMENT)
        } else {
            prependText
        }
        return TextEdit(Range(minStartPosition, main.range.end), escapedPrepend + main.newText)
    }
}
