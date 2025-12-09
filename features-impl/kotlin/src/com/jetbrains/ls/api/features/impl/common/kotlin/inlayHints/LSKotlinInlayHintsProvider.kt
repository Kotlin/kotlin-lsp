// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.inlayHints

import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.core.util.positionByOffset
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.inlayHints.LSInlayHintsCommonImpl
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.impl.common.utils.getLspLocationForDefinition
import com.jetbrains.lsp.protocol.*
import org.jetbrains.kotlin.idea.codeInsight.hints.KotlinFqnDeclarativeInlayActionHandler
import org.jetbrains.kotlin.idea.k2.codeinsight.hints.*

internal object LSKotlinInlayHintsProvider : LSInlayHintsCommonImpl(
    supportedLanguages = setOf(LSKotlinLanguage),
    uniqueId = LSUniqueConfigurationEntry.UniqueId("kotlin-inlay-hints")
) {
    override fun createProviders(options: InlayOptions): List<Provider> {
        return buildList {
            add(Provider(KtReferencesTypeHintsProvider(), KotlinHintFactory(kind = InlayHintKind.Type)))
            add(Provider(KtLambdasHintsProvider(printSeparatingSpace = true), KotlinHintFactory(kind = InlayHintKind.Type)))
            if (options.isEnabled("hints.parameters")) {
                add(Provider(KtParameterHintsProvider(), KotlinHintFactory(paddingRight = true, kind = InlayHintKind.Parameter)))
            }
            add(Provider(KtDefaultParameterInlayHintsProvider(), KotlinHintFactory(paddingRight = true, kind = InlayHintKind.Parameter)))
            add(Provider(KtCallChainHintsProvider(), KotlinHintFactory()))
            add(Provider(KtValuesHintsProvider(), KotlinHintFactory(paddingLeft = true, paddingRight = true)))
        }
    }

    override val lspConfigurationParamsSection: String = "jetbrains.kotlin"


    private class KotlinHintFactory(
        private val paddingLeft: Boolean? = null,
        private val paddingRight: Boolean? = null,
        private val kind: InlayHintKind? = null,
    ) : HintFactory {

        override fun createHint(
            presentation: Presentation,
            psiFile: PsiFile,
            document: Document,
            data: InlayHintResolveData
        ): InlayHint {
            val position = presentation.position.toOriginal() as? InlineInlayPosition ?: error("Only inline hints are supported")
            val labels = presentation.hints.map { hintToLabel(it, psiFile, resolve = false) }
            return InlayHint(
                position = document.positionByOffset(position.offset),
                label = OrString.of(labels),
                kind = kind,
                textEdits = null,
                tooltip = presentation.tooltip?.let { OrString(it) },
                paddingLeft = paddingLeft,
                paddingRight = paddingRight,
                data = LSP.json.encodeToJsonElement(InlayHintResolveData.serializer(), data),
            )
        }

        override fun resolveHint(hint: InlayHint, presentation: Presentation, psiFile: PsiFile, document: Document): InlayHint {
            val labels = presentation.hints.map { hintToLabel(it, psiFile, resolve = true) }
            return hint.copy(label = OrString.of(labels))
        }

        private fun hintToLabel(hint: Hint, psiFile: PsiFile, resolve: Boolean): InlayHintLabelPart = when (hint) {
            is Hint.TextHint -> {
                InlayHintLabelPart(
                    hint.text,
                    tooltip = null,
                    location = when {
                        resolve -> getLocationTarget(hint.actionData, psiFile)?.getLspLocationForDefinition()
                        else -> null
                    },
                    command = null
                )
            }

            is Hint.ListHint -> error("List hints are not supported")
        }

        private fun getLocationTarget(
            actionData: InlayActionDataSerializable?,
            psiFile: PsiFile,
        ): PsiElement? = when (actionData?.handlerId) {
            KotlinFqnDeclarativeInlayActionHandler.HANDLER_NAME -> {
                KotlinFqnDeclarativeInlayActionHandler.getNavigationElement(psiFile, actionData.payload.toOriginal(psiFile.project))
            }

            PsiPointerInlayActionNavigationHandler.HANDLER_ID -> {
                val payload = actionData.payload as InlayActionPayloadDataSerializable.PsiPointerInlayActionPayloadSerializable
                payload.pointer.restore(psiFile.project)
            }

            else -> null
        }
    }
}
