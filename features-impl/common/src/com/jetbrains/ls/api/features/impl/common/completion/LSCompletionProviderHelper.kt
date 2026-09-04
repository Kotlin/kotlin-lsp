// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.Language
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.jetbrains.analyzer.codeServer.createCompletionProcess
import com.jetbrains.analyzer.codeServer.insertCompletion
import com.jetbrains.analyzer.codeServer.takeCaretInsideTabOutScope
import com.jetbrains.analyzer.codeServer.performCompletion
import com.jetbrains.ls.api.core.LSAnalysisContext
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.withAnalysisContextAndFileSettings
import com.jetbrains.ls.api.features.LspServerBundle
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.completion.LSCompletionCandidate
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.hover.LSHoverProviderBase.LSMarkdownDocProvider.Companion.getMarkdownDoc
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.textEdits.TextEditsComputer
import com.jetbrains.ls.api.core.util.isSource
import com.jetbrains.ls.snapshot.api.impl.core.CompletionItemId
import com.jetbrains.ls.snapshot.api.impl.core.CompletionItemWithObject
import com.jetbrains.ls.snapshot.api.impl.core.LatestCompletionSessionComponent
import com.jetbrains.ls.api.features.impl.common.utils.showDocumentIfSupported
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.ApplyWorkspaceEditParams
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionItemLabelDetails
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionParams
import com.jetbrains.lsp.protocol.CompletionTriggerKind
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.MarkupContent
import com.jetbrains.lsp.protocol.MarkupKindType
import com.jetbrains.lsp.protocol.MessageType
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.ShowDocumentParams
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.ShowMessageParams
import com.jetbrains.lsp.protocol.StringOrMarkupContent
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class LSCompletionProviderHelper(
    private val language: LSLanguage,
    private val uniqueId: LSUniqueConfigurationEntry.UniqueId,
    private val applyCompletionCommandKey: String,
    private val completionDataKey: String,
    private val restoreTabOutScopeCaret: Boolean = false,
    private val completionChar: Char = Lookup.AUTO_INSERT_SELECT_CHAR,
) {

    interface FileForModificationProvider {
        context(analysisContext: LSAnalysisContext)
        fun <T> withFileForModification(physicalPsiFile: PsiFile, action: (fileForModification: PsiFile) -> T): T
    }

    /**
     * Context of a globally arranged lookup element.
     *
     * [physicalPsiFile] and [caretOffset] refer to the source document.
     * [prefix] is computed lazily so an override that falls back to standard conversion
     * does not resolve the item matcher before legacy filtering and rendering.
     */
    class LookupElementContext internal constructor(
        val lookupElement: LookupElement,
        val physicalPsiFile: PsiFile,
        val caretOffset: Int,
        val sortText: String,
        itemMatcherProvider: () -> PrefixMatcher,
    ) {
        private val itemMatcher: PrefixMatcher by lazy(LazyThreadSafetyMode.NONE, itemMatcherProvider)

        val prefix: String
            get() = itemMatcher.prefix
    }

    /** An optional replacement for the standard retained-object conversion of a lookup element. */
    sealed interface LookupElementOverride {
        /** Sends [item] without retaining the lookup element in the completion session. */
        data class Item(val item: CompletionItem) : LookupElementOverride

        /** Omits the lookup element from the response. */
        data object Skip : LookupElementOverride
    }

    fun createCommandDescriptors(fileForModificationProvider: FileForModificationProvider): List<LSCommandDescriptor> = listOf(
            LSCommandDescriptor(
                title = LspServerBundle.message("command.apply.completion.item"),
                name = applyCompletionCommandKey,
                executor = { arguments ->
                    val server = contextOf<LSServer>()
                    require(arguments.size == 1) { "Expected 1 argument, got: ${arguments.size}" }
                    val id = arguments[0]
                    when (val completion = server[LatestCompletionSessionComponent].get(CompletionItemId.fromJson(id)) as LSCompletion?) {
                        null -> {
                            lspClient.notify(
                                notificationType = ShowMessageNotificationType,
                                params = ShowMessageParams(MessageType.Error, LspServerBundle.message("error.session.expired")),
                            )
                        }

                        else -> {
                            server.withAnalysisContextAndFileSettings(completion.params.textDocument.uri.uri) {
                                val insertionResult = applyCompletion(completion, fileForModificationProvider)
                                lspClient.request(
                                    ApplyEditRequests.ApplyEdit,
                                    ApplyWorkspaceEditParams(
                                        label = null,
                                        edit = WorkspaceEdit(
                                            changes = mapOf(completion.params.textDocument.uri to insertionResult.edits)
                                        )
                                    )
                                )
                                lspClient.showDocumentIfSupported(
                                    ShowDocumentParams(
                                        uri = completion.params.textDocument.uri.uri,
                                        external = false,
                                        takeFocus = true,
                                        selection = Range(insertionResult.caretPosition, insertionResult.caretPosition)
                                    )
                                )
                            }
                        }
                    }

                    JsonPrimitive(true)
                }
            )
        )

    /**
     * Runs standard completion and optionally overrides conversion of individual globally arranged lookup elements.
     * Returning `null` applies that filter and the standard legacy conversion,
     * while [LookupElementOverride.Item] and [LookupElementOverride.Skip] bypass it.
     */
    context(server: LSServer)
    suspend fun provideCompletion(
        params: CompletionParams,
        lookupElementOverride: ((LookupElementContext) -> LookupElementOverride?)? = null,
    ): CompletionList {
        if (!params.textDocument.isSource()) return CompletionList.EMPTY

        val entries = server.withAnalysisContextAndFileSettings(params.textDocument.uri.uri) {
            readAction {
                params.textDocument.findVirtualFile()?.let { file ->
                    file.findPsiFile(project)?.let { psiFile ->
                        file.findDocument()?.offsetByPosition(params.position)?.let { offset ->
                            psiFile to offset
                        }
                    }
                }
            }?.let { (psiFile, offset) ->
                val completionProcess = edtWriteAction {
                    createCompletionProcess(
                        project = project,
                        file = psiFile,
                        offset = offset,
                        invocationCount = computeInvocationCount(params.context?.triggerKind)
                    )
                }
                readAction {
                    val lookupElements = performCompletion(completionProcess)
                    lookupElements.mapIndexedNotNull { index, lookup ->
                        val sortText = getSortedFieldByIndex(index)
                        val itemMatcherProvider = { completionProcess.arranger.itemMatcher(lookup) }
                        val override = lookupElementOverride?.invoke(
                            LookupElementContext(
                                lookupElement = lookup,
                                physicalPsiFile = psiFile,
                                caretOffset = offset,
                                sortText = sortText,
                                itemMatcherProvider = itemMatcherProvider,
                            )
                        )
                        when (override) {
                            is LookupElementOverride.Item -> CompletionEntry(override.item, null)
                            LookupElementOverride.Skip -> null
                            null -> createLegacyCompletionEntry(params, lookup, sortText, itemMatcherProvider)
                        }
                    }
                }
            } ?: emptyList()
        }
        server.update(LatestCompletionSessionComponent) { state ->
            state.replace(entries.mapNotNull { it.itemWithObject })
        }
        return CompletionList(isIncomplete = true, items = entries.map { it.item })
    }

    context(server: LSServer)
    private fun createLegacyCompletionEntry(
        params: CompletionParams,
        lookup: LookupElement,
        sortText: String,
        itemMatcherProvider: () -> PrefixMatcher,
    ): CompletionEntry {
        val lookupPresentation = LookupElementPresentation().also(lookup::renderElement)
        val itemMatcher = itemMatcherProvider()
        val key = server[LatestCompletionSessionComponent].nextId()
        val itemWithObject = CompletionItemWithObject(
            item = CompletionItem(
                label = lookupPresentation.itemText ?: lookup.lookupString,
                sortText = sortText,
                labelDetails = CompletionItemLabelDetails(
                    detail = lookupPresentation.tailText,
                    description = lookupPresentation.typeText,
                ),
                kind = LSCompletionItemKindProvider.getKind(CompletionCandidate(lookup, language)),
                textEdit = CompletionItem.Edit.emptyAtPosition(params.position),
                command = Command(
                    LspServerBundle.message("command.apply.completion"),
                    command = applyCompletionCommandKey,
                    arguments = listOf(key.toJson())
                ),
                data = JsonObject(
                    mapOf(
                        completionDataKey to key.toJson(),
                        ResolveDataWithConfigurationEntryId::configurationEntryId.name to LSP.json.encodeToJsonElement(uniqueId)
                    )
                ),
            ),
            key = key,
            obj = LSCompletion(params, lookup, itemMatcher)
        )
        return CompletionEntry(itemWithObject.item, itemWithObject)
    }

    context(server: LSServer)
    suspend fun resolveCompletion(completionItem: CompletionItem, fileForModificationProvider: FileForModificationProvider): CompletionItem? {
        val completionDataValue = completionItem.data?.jsonObject?.get(completionDataKey) ?: return completionItem

        return (server[LatestCompletionSessionComponent].get(CompletionItemId.fromJson(completionDataValue)) as LSCompletion?)?.let { completionData ->
            server.withAnalysisContextAndFileSettings(completionData.params.textDocument.uri.uri) {
                val completionItem = completionItem.copy(
                    documentation = readAction { computeDocumentation(completionData.lookup) },
                )
                // https://youtrack.jetbrains.com/issue/LSP-319/Fix-completion-in-Air
                val isAir = server.config.clientName == "JetBrains Air"
                if (isAir) {
                    val insRes = applyCompletion(completionData, fileForModificationProvider)
                    completionItem.copy(
                        additionalTextEdits = insRes.edits,
                        command = Command(
                            title = LspServerBundle.message("command.move.cursor"),
                            command = "cursorMove",
                            arguments = listOf(
                                buildJsonObject {
                                    put("to", "offset")
                                    put("value", insRes.caretOffset)
                                }
                            )
                        )
                    )
                } else {
                    completionItem
                }
            }
        }
    }

    private fun computeDocumentation(lookup: LookupElement): StringOrMarkupContent? {
        return lookup.psiElement
            ?.let { getMarkdownDoc(it) }
            ?.let { StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, it)) }
    }

    context(analysisContext: LSAnalysisContext)
    private fun applyCompletion(completion: LSCompletion, fileForModificationProvider: FileForModificationProvider): CompletionInsertionResult =
        invokeAndWaitIfNeeded {
            runWriteAction {
                val physicalVirtualFile = requireNotNull(completion.params.textDocument.findVirtualFile()) {
                    "virtual file not found for ${completion.params.textDocument}"
                }
                val physicalPsiFile = requireNotNull(physicalVirtualFile.findPsiFile(project)) {
                    "psi file not found for $physicalVirtualFile"
                }
                val initialText = physicalPsiFile.text

                fileForModificationProvider.withFileForModification(
                    physicalPsiFile,
                ) { fileForModification ->
                    val document = fileForModification.fileDocument
                    val caretBefore = document.offsetByPosition(completion.params.position)
                    val completionProcess = createCompletionProcess(project, fileForModification, caretBefore)
                    completionProcess.arranger.registerMatcher(
                        completion.lookup,
                        CamelHumpMatcher(completion.itemMatcher.prefix)
                    )
                    insertCompletion(project, fileForModification, completion.lookup, completionProcess.parameters!!, completionChar)
                    val edits = TextEditsComputer.computeTextEdits(initialText, fileForModification.text)
                    val caretAfter = if (restoreTabOutScopeCaret) {
                        takeCaretInsideTabOutScope(completionProcess.parameters!!.editor) ?: completionProcess.caret.offset
                    } else {
                        completionProcess.caret.offset
                    }
                    CompletionInsertionResult(edits, document.positionByOffset(caretAfter), caretAfter)
                }
            }
        }

    private fun computeInvocationCount(triggerKind: CompletionTriggerKind?): Int {
        return when (triggerKind) {
            CompletionTriggerKind.TriggerCharacter,
            CompletionTriggerKind.TriggerForIncompleteCompletions -> 0

            else -> 1
        }
    }

    private data class CompletionEntry(
        val item: CompletionItem,
        val itemWithObject: CompletionItemWithObject<LSCompletion>?,
    )

    private data class CompletionCandidate(private val lookup: LookupElement, private val lsLanguage: LSLanguage) : LSCompletionCandidate {
        override val language: Language = lsLanguage.intellijLanguage
        override val result: Any = lookup.psiElement ?: lookup.`object`
    }

    private data class CompletionInsertionResult(val edits: List<TextEdit>, val caretPosition: Position, val caretOffset: Int)
}
