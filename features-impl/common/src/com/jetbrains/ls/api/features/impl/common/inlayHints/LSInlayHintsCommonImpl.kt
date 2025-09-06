// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.inlayHints

import com.intellij.codeInsight.hints.declarative.*
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findDocument
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.findVirtualFile
import com.jetbrains.ls.api.core.util.toTextRange
import com.jetbrains.ls.api.core.withAnalysisContext
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.inlayHints.LSInlayHintsProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.resolve.ResolveDataWithConfigurationEntryId
import com.jetbrains.ls.api.features.utils.PsiSerializablePointer
import com.jetbrains.ls.api.features.utils.toPsiPointer
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.lspClient
import com.jetbrains.lsp.protocol.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

abstract class LSInlayHintsCommonImpl(
    override val supportedLanguages: Set<LSLanguage>,
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId
) : LSInlayHintsProvider {

    /**
     * Provider order should be stable so multiple invocations return the same result.
     */
    protected abstract fun createProviders(options: InlayOptions): List<Provider>

    protected abstract val lspConfigurationParamsSection: String?

    protected class Provider(
        val intellijProvider: InlayHintsProvider,
        val factory: HintFactory
    )

    context(_: LSServer, _: LspHandlerContext)
    override fun getInlayHints(params: InlayHintParams): Flow<InlayHint> = flow {
        val result = mutableListOf<InlayHint>()
        val options = requestEnabledInlayOptions(params.textDocument)

        withAnalysisContext {
            runReadAction a@{
                val file = params.textDocument.findVirtualFile() ?: return@a
                val psiFile = file.findPsiFile(project) ?: return@a
                val document = file.findDocument() ?: return@a
                val textRange = params.range.toTextRange(document)
                val editor = ImaginaryEditor(project, document)

                for ((providerIndex, provider) in createProviders(options).withIndex()) {
                    val presentations = collectHints(provider, psiFile, editor, textRange, options)
                    for (presentation in presentations) {
                        result += provider.factory.createHint(
                            presentation,
                            psiFile,
                            document,
                            InlayHintResolveData(params, presentation, providerIndex, options,uniqueId),
                        ) ?: continue
                    }
                }
            }
        }
        result.forEach { emit(it) }
    }

    context(_: LspHandlerContext)
    private suspend fun requestEnabledInlayOptions(document: TextDocumentIdentifier): InlayOptions {
        val raw = lspClient.request(
            Workspace.Configuration,
            ConfigurationParams(
                listOf(
                    ConfigurationItem(document.uri.uri, lspConfigurationParamsSection),
                )
            )
        )
        return InlayOptions.create(raw)
    }

    context(_: LSServer, _: LspHandlerContext)
    override suspend fun resolveInlayHint(hint: InlayHint): InlayHint? {
        val dataJson = hint.data ?: return null
        val data = LSP.json.decodeFromJsonElement(InlayHintResolveData.serializer(), dataJson)
        return withAnalysisContext {
            runReadAction a@{
                val file = data.params.textDocument.findVirtualFile() ?: return@a null
                val psiFile = file.findPsiFile(project) ?: return@a null
                val document = file.findDocument() ?: return@a null
                val provider = createProviders(data.options)[data.providerIndex]
                provider.factory.resolveHint(hint, data.presentation, psiFile, document)
            }
        }
    }

    protected interface HintFactory {
        fun createHint(presentation: Presentation, psiFile: PsiFile, document: Document, data: InlayHintResolveData): InlayHint?

        fun resolveHint(hint: InlayHint, presentation: Presentation, psiFile: PsiFile, document: Document): InlayHint? = null
    }

    private fun collectHints(
        provider: Provider,
        psiFile: PsiFile,
        editor: ImaginaryEditor,
        textRange: TextRange,
        inlayOptions: InlayOptions,
    ): List<Presentation> {
        val collector = provider.intellijProvider.createCollector(psiFile, editor) ?: return emptyList()
        val sink = LSCommonInlayTreeSink(inlayOptions)
        when (collector) {
            is SharedBypassCollector -> {
                val traverser = SyntaxTraverser.psiTraverser(psiFile).onRange(textRange)
                for (element in traverser) {
                    collector.collectFromElement(element, sink)
                }
            }

            else -> {
                error("Unsupported collector type: ${collector::class.simpleName}")
            }
        }
        return sink.presentations
    }

    private class LSCommonInlayTreeSink(
        private val inlayOptions: InlayOptions,
    ) : InlayTreeSink {
        private val _presentations: MutableList<Presentation> = mutableListOf()

        val presentations: List<Presentation> get() = _presentations

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            _presentations += Presentation(InlayPositionSerializable.fromInlayPosition(position), tooltip, LSCommonTreeBuilder().apply(builder).hints)
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) {
           if (inlayOptions.isEnabled(optionId)) {
               block()
           }
        }
    }


    @Serializable
    protected class InlayOptions(private val enabled: Set<String>) {
        fun isEnabled(optionId: String): Boolean {
            return enabled.contains(optionId)
        }

        companion object {
            fun create(raw: List<JsonElement?>): InlayOptions {
                val enabled = mutableSetOf<String>()

                fun handleRecursively(element: JsonElement, path: PersistentList<String>) {
                    when (element) {
                        is JsonObject -> {
                            for ((key, value) in element) {
                                handleRecursively(value, path.add(key))
                            }
                        }

                        is JsonPrimitive if element.booleanOrNull == true -> {
                            enabled += path.joinToString(".")
                        }

                        else -> {}
                    }
                }

                for (element in raw) {
                    if (element == null) continue
                    handleRecursively(element, persistentListOf())
                }

                return InlayOptions(enabled)
            }
        }
    }

    private class LSCommonTreeBuilder() : PresentationTreeBuilder {
        private val _hints: MutableList<Hint> = mutableListOf()

        val hints: List<Hint> get() = _hints

        override fun list(builder: PresentationTreeBuilder.() -> Unit) {
            _hints += Hint.ListHint(LSCommonTreeBuilder().apply(builder)._hints)
        }

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit
        ) {
            error("Unsupported for LSP for now")
        }

        override fun text(text: String, actionData: InlayActionData?) {
            _hints += Hint.TextHint(
                text,
                actionData?.let { InlayActionDataSerializable.fromInlayActionData(it) },
            )
        }

        override fun clickHandlerScope(
            actionData: InlayActionData,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            error("Unsupported for LSP for now")
        }
    }

    @Serializable
    protected class Presentation(
        val position: InlayPositionSerializable,
        val tooltip: String?,
        val hints: List<Hint>,
    )

    @Serializable
    protected sealed interface InlayPositionSerializable {
        fun toOriginal(): InlayPosition

        @Serializable
        data class InlineInlayPositionSerializable(
            val offset: Int,
            val relatedToPrevious: Boolean,
            val priority: Int,
        ) : InlayPositionSerializable {
            override fun toOriginal(): InlayPosition = InlineInlayPosition(offset, relatedToPrevious, priority)
        }

        @Serializable
        data class EndOfLinePositionSerializable(
            val line: Int,
            val priority: Int,
        ) : InlayPositionSerializable {
            override fun toOriginal(): InlayPosition = EndOfLinePosition(line, priority)
        }

        @Serializable
        data class AboveLineIndentedPositionSerializable(
            val offset: Int, val verticalPriority: Int,
            val priority: Int,
        ) :
            InlayPositionSerializable {
            override fun toOriginal(): InlayPosition = AboveLineIndentedPosition(offset, verticalPriority, priority)
        }

        companion object {
            fun fromInlayPosition(position: InlayPosition): InlayPositionSerializable {
                return when (position) {
                    is InlineInlayPosition -> InlineInlayPositionSerializable(position.offset, position.relatedToPrevious, position.priority)
                    is EndOfLinePosition -> EndOfLinePositionSerializable(position.line, position.priority)
                    is AboveLineIndentedPosition -> AboveLineIndentedPositionSerializable(position.offset, position.verticalPriority, position.priority)
                }
            }
        }
    }

    @Serializable
    protected data class InlayActionDataSerializable(val payload: InlayActionPayloadDataSerializable, val handlerId: String) {
        companion object {
            fun fromInlayActionData(data: InlayActionData): InlayActionDataSerializable {
                return InlayActionDataSerializable(
                    payload = when (val payload = data.payload) {
                        is StringInlayActionPayload -> InlayActionPayloadDataSerializable.StringInlayActionPayloadSerializable(payload.text)
                        is PsiPointerInlayActionPayload ->
                            InlayActionPayloadDataSerializable.PsiPointerInlayActionPayloadSerializable(
                                PsiSerializablePointer.fromPsiPointer(payload.pointer)
                            )

                        else -> error("Unsupported payload type: ${payload::class.simpleName}")
                    },
                    handlerId = data.handlerId,
                )
            }
        }
    }

    @Serializable
    protected sealed interface InlayActionPayloadDataSerializable {
        fun toOriginal(project: Project): InlayActionPayload

        @Serializable
        data class StringInlayActionPayloadSerializable(val text: String) : InlayActionPayloadDataSerializable {
            override fun toOriginal(project: Project): InlayActionPayload {
                return StringInlayActionPayload(text)
            }
        }

        @Serializable
        data class PsiPointerInlayActionPayloadSerializable(val pointer: PsiSerializablePointer) : InlayActionPayloadDataSerializable {
            override fun toOriginal(project: Project): InlayActionPayload {
                return PsiPointerInlayActionPayload(pointer.toPsiPointer(project))
            }
        }
    }

    @Serializable
    protected data class InlayHintResolveData(
        val params: InlayHintParams,
        val presentation: Presentation,
        val providerIndex: Int,
        val options: InlayOptions, // we need it to be the same between the main and resolve requests, so we pass it instead of rerequesting
        override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
    ) : ResolveDataWithConfigurationEntryId


    @Serializable
    protected sealed interface Hint {
        @Serializable
        class ListHint(val hints: List<Hint>) : Hint

        @Serializable
        class TextHint(
            val text: String,
            val actionData: InlayActionDataSerializable?,
        ) : Hint
    }
}