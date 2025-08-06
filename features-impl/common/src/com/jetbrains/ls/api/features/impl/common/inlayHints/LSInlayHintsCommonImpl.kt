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
import com.jetbrains.lsp.protocol.InlayHint
import com.jetbrains.lsp.protocol.InlayHintParams
import com.jetbrains.lsp.protocol.LSP
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

abstract class LSInlayHintsCommonImpl(
    override val supportedLanguages: Set<LSLanguage>,
    override val uniqueId: LSUniqueConfigurationEntry.UniqueId
) : LSInlayHintsProvider {

    /**
     * Provider order should be stable so multiple invocations return the same result.
     */
    abstract fun createProviders(): List<Provider>

    class Provider(
        val intellijProvider: InlayHintsProvider,
        val factory: HintFactory
    )

    context(_: LSServer)
    override fun getInlayHints(params: InlayHintParams): Flow<InlayHint> = flow {
        val result = mutableListOf<InlayHint>()
        withAnalysisContext {
            runReadAction a@{
                val file = params.textDocument.findVirtualFile() ?: return@a
                val psiFile = file.findPsiFile(project) ?: return@a
                val document = file.findDocument() ?: return@a
                val textRange = params.range.toTextRange(document)
                val editor = ImaginaryEditor(project, document)

                for ((providerIndex, provider) in createProviders().withIndex()) {
                    val presentations = collectHints(provider, psiFile, editor, textRange)
                    for (presentation in presentations) {
                        result += provider.factory.createHint(
                            presentation,
                            psiFile,
                            document,
                            InlayHintResolveData(params, presentation, providerIndex, uniqueId),
                        ) ?: continue
                    }
                }
            }
        }
        result.forEach { emit(it) }
    }

    context(_: LSServer)
    override suspend fun resolveInlayHint(hint: InlayHint): InlayHint? {
        val dataJson = hint.data ?: return null
        val data = LSP.json.decodeFromJsonElement(InlayHintResolveData.serializer(), dataJson)
        return withAnalysisContext {
            runReadAction a@{
                val file = data.params.textDocument.findVirtualFile() ?: return@a null
                val psiFile = file.findPsiFile(project) ?: return@a null
                val document = file.findDocument() ?: return@a null
                val provider = createProviders()[data.providerIndex]
                provider.factory.resolveHint(hint, data.presentation, psiFile, document)
            }
        }
    }

    interface HintFactory {
        fun createHint(presentation: Presentation, psiFile: PsiFile, document: Document, data: InlayHintResolveData): InlayHint?

        fun resolveHint(hint: InlayHint, presentation: Presentation, psiFile: PsiFile, document: Document): InlayHint? = null
    }

    private fun collectHints(
        provider: Provider,
        psiFile: PsiFile,
        editor: ImaginaryEditor,
        textRange: TextRange,
    ): List<Presentation> {
        val collector = provider.intellijProvider.createCollector(psiFile, editor) ?: return emptyList()
        val sink = Sink()
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

    private class Sink : InlayTreeSink {
        private val _presentations: MutableList<Presentation> = mutableListOf()

        val presentations: List<Presentation> get() = _presentations

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit
        ) {
            _presentations += Presentation(InlayPositionSerializable.fromInlayPosition(position), tooltip, TreeBuilder().apply(builder).hints)
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) {
            // TODO
            block()
        }
    }

    private class TreeBuilder() : PresentationTreeBuilder {
        private val _hints: MutableList<Hint> = mutableListOf()

        val hints: List<Hint> get() = _hints

        override fun list(builder: PresentationTreeBuilder.() -> Unit) {
            _hints += Hint.ListHint(TreeBuilder().apply(builder)._hints)
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
    class Presentation(
        val position: InlayPositionSerializable,
        val tooltip: String?,
        val hints: List<Hint>,
    )

    @Serializable
    sealed interface InlayPositionSerializable {
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
    data class InlayActionDataSerializable(val payload: InlayActionPayloadDataSerializable, val handlerId: String) {
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
    sealed interface InlayActionPayloadDataSerializable {
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
    data class InlayHintResolveData(
        val params: InlayHintParams,
        val presentation: Presentation,
        val providerIndex: Int,
        override val configurationEntryId: LSUniqueConfigurationEntry.UniqueId,
    ) : ResolveDataWithConfigurationEntryId


    @Serializable
    sealed interface Hint {
        @Serializable
        class ListHint(val hints: List<Hint>) : Hint

        @Serializable
        class TextHint(
            val text: String,
            val actionData: InlayActionDataSerializable?,
        ) : Hint
    }
}