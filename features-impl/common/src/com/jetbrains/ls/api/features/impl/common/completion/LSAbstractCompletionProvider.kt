// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.completion

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.createCompletionProcess
import com.intellij.codeInsight.completion.performCompletion
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.util.ObjectUtils
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.offsetByPosition
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.commands.LSCommandDescriptor
import com.jetbrains.ls.api.features.commands.LSCommandDescriptorProvider
import com.jetbrains.ls.api.features.completion.LSCompletionItemKindProvider
import com.jetbrains.ls.api.features.completion.LSCompletionProvider
import com.jetbrains.ls.api.features.impl.common.hover.AbstractLSHoverProvider.LSMarkdownDocProvider.Companion.getMarkdownDoc
import com.jetbrains.ls.api.features.utils.isSource
import com.jetbrains.ls.snapshot.api.impl.core.SessionDataEntity
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong

abstract class LSAbstractCompletionProvider : LSCompletionProvider, LSCommandDescriptorProvider {
    override val supportsResolveRequest: Boolean get() = false

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
                        val completionItems = lookupElements.mapIndexed { i, lookup ->
                            val lookupPresentation = LookupElementPresentation().also {
                                lookup.renderElement(it)
                            }

                            val itemMatcher = completionProcess.arranger.itemMatcher(lookup)
                            val data = CompletionData(params, lookup, itemMatcher)

                            val id = cacheCompletionData(data)

                            CompletionItem(
                                label = lookupPresentation.itemText ?: lookup.lookupString,
                                sortText = getSortedFieldByIndex(i),
                                labelDetails = CompletionItemLabelDetails(
                                    detail = lookupPresentation.tailText,
                                    description = lookupPresentation.typeText,
                                ),
                                documentation = computeDocumentation(lookup),
                                kind = lookup.psiElement?.let { LSCompletionItemKindProvider.getKind(it) },
                                textEdit = emptyTextEdit(params.position),
                                command = Command(
                                    "Apply Completion",
                                    command = completionCommand,
                                    arguments = listOf(
                                        LSP.json.encodeToJsonElement(id),
                                    )
                                ),
                            )
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
        private const val PER_CLIENT_COMPLETION_CACHE_SIZE = 1000L
        private val COMPLETION_CACHE_KEY = ObjectUtils.sentinel("COMPLETION_CACHE_KEY")
        private val COMPLETION_ID_GENERATOR_KEY = ObjectUtils.sentinel("COMPLETION_ID_GENERATOR_KEY")
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

        fun emptyTextEdit(position: Position): CompletionItem.Edit {
            val range = Range(position, position)
            return CompletionItem.Edit.InsertReplace(InsertReplaceEdit("", range, range))
        }

        fun <T : Any> cacheCompletionData(data: T): Long {
            val sessionData = SessionDataEntity.single().map

            @Suppress("UNCHECKED_CAST")
            val completionCache = sessionData.getOrPut(COMPLETION_CACHE_KEY) {
                Caffeine.newBuilder()
                    .maximumSize(PER_CLIENT_COMPLETION_CACHE_SIZE)
                    .build<Long, T>()
            } as Cache<Long, T>

            val id = generateCacheId()
            completionCache.put(id, data)
            return id
        }

        fun <T : Any> getCompletionData(id: Long): T? {
            val userData = SessionDataEntity.single().map

            @Suppress("UNCHECKED_CAST")
            val completionCache = userData[COMPLETION_CACHE_KEY] as Cache<Long, T>
            val completionData = completionCache.getIfPresent(id)
            return completionData
        }

        private fun generateCacheId(): Long {
            val sessionData = SessionDataEntity.single().map
            val idGenerator = sessionData.getOrPut(COMPLETION_ID_GENERATOR_KEY) { AtomicLong(0L) } as AtomicLong
            return idGenerator.incrementAndGet()
        }
    }

    abstract val completionCommand: String

    context(_: LspHandlerContext, _: LSServer)
    protected abstract suspend fun applyCompletion(completionData: CompletionData)

    override val commandDescriptors: List<LSCommandDescriptor>
        get() = listOf(
            LSCommandDescriptor(
                title = "Apply Completion Item",
                name = completionCommand,
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
            )
        )

    protected open fun computeDocumentation(lookup: LookupElement): StringOrMarkupContent? {
        return lookup.psiElement
            ?.let { getMarkdownDoc(it) }
            ?.let { StringOrMarkupContent(MarkupContent(MarkupKindType.Markdown, it)) }
    }

    protected data class CompletionData(val params: CompletionParams, val lookup: LookupElement, val itemMatcher: PrefixMatcher)
}
