// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.javaBase.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.LightModCompletionServiceImpl
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.modcompletion.ModCompletionItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.StubTextInconsistencyException
import com.intellij.util.containers.addIfNotNull
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider.Companion.cacheCompletionData
import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider.Companion.emptyTextEdit
import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider.Companion.getCompletionData
import com.jetbrains.ls.api.features.impl.javaBase.language.LSJavaLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.kotlinLsp.requests.core.ModCommandData
import com.jetbrains.ls.kotlinLsp.requests.core.executeCommand
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

object LSCompletionProviderJavaImpl : LSCompletionProvider, LSCommandDescriptorProvider {
    private const val COMMAND_NAME: String = "jetbrains.java.completion.apply"

    override val supportedLanguages: Set<LSLanguage>
        get() = setOf(LSJavaLanguage)
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId
        get() = LSUniqueConfigurationEntry.UniqueId("IntelliJJavaCompletionProvider")

    override val supportsResolveRequest: Boolean
        get() = false

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList {
        return if (!params.textDocument.isSource()) {
            CompletionList.EMPTY_COMPLETE
        } else {
            val items = withAnalysisContext(params.textDocument.uri.uri) {
                readAction {
                    val virtualFile = params.textDocument.findVirtualFile() ?: return@readAction null
                    val psiFile = virtualFile.findPsiFile(project) ?: return@readAction null
                    val document = psiFile.fileDocument
                    val position = document.offsetByPosition(params.position)
                    val list = mutableListOf<CompletionItem>()
                    val prefixStart = computePrefixStart(document, position)
                    val actionContext = ActionContext(project, psiFile, position, TextRange.create(prefixStart, position), null)

                    StubTextInconsistencyException.checkStubTextConsistency(psiFile)

                    LightModCompletionServiceImpl.getItems(psiFile, prefixStart, position, 1, CompletionType.BASIC) { item ->
                        val presentation = item.presentation()

                        var detail = presentation.mainText.toText()
                        var label = item.mainLookupString()
                        if (detail.startsWith(label)) {
                            detail = detail.substring(label.length)
                        } else {
                            label = detail
                            detail = ""
                        }
                        val command = item.perform(actionContext, ModCompletionItem.DEFAULT_INSERTION_CONTEXT)
                        val (edits, rest, changedFiles) = 
                            splitModCommand(psiFile, prefixStart, position, command)
                        val id = cacheCompletionData(CompletionData(rest, changedFiles))
                        val lspItem = CompletionItem(
                            label = label,
                            labelDetails = CompletionItemLabelDetails(
                                detail = detail,
                                description = presentation.detailText.toText(),
                            ),
                            kind = (item.contextObject() as? PsiElement)?.let { LSCompletionItemKindProvider.getKind(it) },
                            filterText = item.mainLookupString(),
                            insertText = "",
                            textEdit = emptyTextEdit(params.position),
                            additionalTextEdits = edits,
                            command = if (rest.isEmpty) null else Command(
                                "Apply Completion",
                                command = COMMAND_NAME,
                                arguments = listOf(
                                    LSP.json.encodeToJsonElement(id),
                                )
                            )
                        )
                        list.add(lspItem)
                    }
                    StubTextInconsistencyException.checkStubTextConsistency(psiFile)
                    list
                }
            }
            if (items == null) {
                CompletionList.EMPTY_COMPLETE
            } else {
                CompletionList(false, null, items)
            }
        }
    }

    fun computePrefixStart(document: Document, position: Int): Int {
        if (position == 0) return 0
        val text = document.charsSequence
        var start = position
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) {
            start--
        }
        return start
    }

    data class Edits(val edits: List<TextEdit>, val rest: ModCommand, val changedFiles: MutableMap<String, String> = mutableMapOf());
    data class MyEdit(val from: Int, val to: Int, val newText: String) {
        fun toTextEdit(document: Document): TextEdit =
            TextEdit(Range(document.positionByOffset(from), document.positionByOffset(to)), newText)
    }

    fun splitModCommand(file: PsiFile, start: Int, caret: Int, command: ModCommand): Edits {
        val unprocessed = mutableListOf<ModCommand>()
        var movedCaret = start
        val document = file.fileDocument
        val fragments = mutableListOf<MyEdit>()
        var removePrefix: MyEdit? = null
        val changedFiles: MutableMap<String, String> = mutableMapOf()
        if (start < caret) {
            removePrefix = MyEdit(start, caret, "")
        }
        val virtualFile = file.virtualFile
        for (cmd in command.unpack()) {
            if (cmd is ModUpdateFileText && cmd.file == virtualFile) {
                val newText = cmd.newText
                var diff = 0
                for (fragment in cmd.updatedRanges) {
                    val string = newText.substring(fragment.offset, fragment.offset + fragment.newLength)
                    val from = fragment.offset + diff
                    var to = fragment.offset + fragment.oldLength + diff
                    if (removePrefix != null && from <= removePrefix.from && to >= removePrefix.from) {
                        to += caret - start
                        diff += caret - start
                        removePrefix = null
                    }
                    fragments.add(MyEdit(from, to, string))
                    diff += fragment.oldLength - fragment.newLength
                }
                movedCaret = cmd.translateOffset(movedCaret, true)
                // Necessary for subsequent navigate command to compute row/column position
                changedFiles[virtualFile.url] = newText 
                continue
            }
            if (cmd is ModNavigate && cmd.file == virtualFile && cmd.caret == movedCaret && 
                cmd.selectionStart == cmd.selectionEnd) {
                continue
            }
            unprocessed.add(cmd)
        }
        fragments.addIfNotNull(removePrefix)
        val edits = fragments.map { it.toTextEdit(document) }
        return Edits(
            edits, unprocessed.fold(ModCommand.nop(), ModCommand::andThen),
            changedFiles)
    }

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = "Apply Completion Item",
                name = COMMAND_NAME,
                executor = { arguments ->
                    require(arguments.size == 1) { "Expected 1 argument, got: ${arguments.size}" }
                    val id = (arguments[0] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: error("Invalid argument, expected a number, got: ${arguments[0]}")
                    val completionData: CompletionData? = getCompletionData(id)
                    when (completionData) {
                        null -> lspClient.notify(
                            ShowMessageNotification,
                            ShowMessageParams(MessageType.Error, "Your completion session has expired, please try again"),
                        )

                        else -> applyCompletion(completionData)
                    }

                    JsonPrimitive(true)
                }
            ))

    context(_: LSServer, _: LspHandlerContext)
    private suspend fun applyCompletion(completionData: CompletionData) {
        val modCommand = completionData.item
        val data = ModCommandData.from(modCommand) ?: return
        executeCommand(data, lspClient, completionData.changedFiles)
    }

    private data class CompletionData(val item: ModCommand, val changedFiles: MutableMap<String, String> = mutableMapOf())
}
