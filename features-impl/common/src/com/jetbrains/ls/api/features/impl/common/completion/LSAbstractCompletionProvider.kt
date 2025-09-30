// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.performCompletion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.completion.CompletionItemData
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

abstract class LSAbstractCompletionProvider : LSCompletionProvider {
    override val supportsResolveRequest: Boolean get() = true

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun provideCompletion(params: CompletionParams): CompletionList {
        return if (!params.textDocument.isSource()) {
            CompletionList.EMPTY_COMPLETE
        } else {
            withAnalysisContext(params.textDocument.uri.uri) {
                readAction {
                    params.textDocument.findVirtualFile()?.let { file ->
                        file.findPsiFile(project)?.let { psiFile ->
                            file.findDocument()?.offsetByPosition(params.position)?.let { offset ->
                                psiFile to offset
                            }
                        }
                    }
                }?.let { (psiFile, offset) ->
                    val completionProcess = invokeAndWaitIfNeeded {
                        createCompletionProcess(
                            project = project,
                            file = psiFile,
                            offset = offset,
                            invocationCount = params.computeInvocationCount()
                        )
                    }
                    readAction {
                        val lookupElements = performCompletion(completionProcess)
                        val completionItems = lookupElements.mapIndexedNotNull { i, lookup ->
                            val lookupPresentation = LookupElementPresentation().also {
                                lookup.renderElement(it)
                            }
                            createAdditionalData(
                                lookupElement = lookup,
                                itemMatcher = completionProcess.arranger.itemMatcher(lookup)
                            )?.let { additionalData ->
                                val data = CompletionItemData(
                                    params = params,
                                    additionalData = additionalData,
                                    configurationEntryId = uniqueId
                                )
                                CompletionItem(
                                    label = lookupPresentation.itemText ?: lookup.lookupString,
                                    sortText = getSortedFieldByIndex(i),
                                    labelDetails = CompletionItemLabelDetails(
                                        detail = lookupPresentation.tailText,
                                        description = lookupPresentation.typeText,
                                    ),
                                    kind = lookup.psiElement?.let { LSCompletionItemKindProvider.getKind(it) },
                                    textEdit = emptyTextEdit(params.position),
                                    data = LSP.json.encodeToJsonElement(CompletionItemData.serializer(), data)
                                )
                            }
                        }
                        CompletionList(
                            isIncomplete = true,
                            items = completionItems,
                        )
                    }
                } ?: EMPTY_COMPLETION_LIST
            }
        }
    }

    private fun CompletionParams.computeInvocationCount(): Int {
        // todo should be based on CompletionParams.context.triggerKind
        return 1
    }


    abstract fun createAdditionalData(lookupElement: LookupElement, itemMatcher: PrefixMatcher): JsonElement?

    companion object {
        private val EMPTY_COMPLETION_LIST = CompletionList(isIncomplete = false, items = emptyList())

        /**
         * According to the LSP spec, completion items are sorted by `sortText` field with string comparison.
         *
         * As items are already sorted by kotlin completion, we just generate string which will be sorted the same way
         */
        private fun getSortedFieldByIndex(index: Int): String {
            return index.toString().padStart(MAX_INT_DIGITS_COUNT, '0')
        }

        private const val MAX_INT_DIGITS_COUNT = Int.MAX_VALUE.toString().length

        private fun emptyTextEdit(position: Position): CompletionItem.Edit {
            val range = Range(position, position)
            return CompletionItem.Edit.InsertReplace(InsertReplaceEdit("", range, range))
        }
    }
}

