// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.inlayHints

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.ls.api.features.impl.common.inlayHints.LSInlayHintsProviderBase
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.lsp.protocol.InlayHintKind
import org.jetbrains.kotlin.idea.codeInsight.hints.KotlinFqnDeclarativeInlayActionHandler
import org.jetbrains.kotlin.idea.k2.codeinsight.hints.*

internal object LSKotlinInlayHintsProvider : LSInlayHintsProviderBase(
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
            if (options.isEnabled("hints.call.chains")) {
                add(Provider(KtCallChainHintsProvider(), KotlinHintFactory()))
            }
            add(Provider(KtValuesHintsProvider(), KotlinHintFactory(paddingLeft = true, paddingRight = true)))
        }
    }

    override val lspConfigurationParamsSection: String = "jetbrains.kotlin"

    private class KotlinHintFactory(
        paddingLeft: Boolean? = null,
        paddingRight: Boolean? = null,
        kind: InlayHintKind? = null,
    ) : HintFactoryBase(paddingLeft, paddingRight, kind) {

        override fun getLocationTarget(
            actionData: InlayActionDataSerializable?,
            psiFile: PsiFile,
        ): PsiElement? = when (actionData?.handlerId) {
            KotlinFqnDeclarativeInlayActionHandler.HANDLER_NAME -> {
                KotlinFqnDeclarativeInlayActionHandler.getNavigationElement(psiFile, actionData.payload.toOriginal(psiFile.project))
            }
            else -> super.getLocationTarget(actionData, psiFile)
        }

        override fun isCollapsingEnabled(options: InlayOptions): Boolean = false
    }
}
