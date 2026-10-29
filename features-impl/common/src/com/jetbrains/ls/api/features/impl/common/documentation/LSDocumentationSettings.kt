// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.documentation

import com.intellij.lang.documentation.ClientDocumentationSettings
import com.intellij.lang.documentation.DocumentationSettings
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Stub impl of [ClientDocumentationSettings].
 *
 * Inside [neutral] the settings turn off every scheme-colored part of the documentation HTML (signatures, code blocks,
 * inline code, code background), so a client can color the HTML with its own scheme.
 *
 * TODO (mbo): handle ide settings ?
 */
internal class LSDocumentationSettings : ClientDocumentationSettings {
    override fun isHighlightingOfQuickDocSignaturesEnabled(): Boolean = !isNeutral()

    override fun isHighlightingOfCodeBlocksEnabled(): Boolean = !isNeutral()

    override fun isSemanticHighlightingOfLinksEnabled(): Boolean = false

    override fun isCodeBackgroundEnabled(): Boolean = !isNeutral()

    override fun getInlineCodeHighlightingMode(): DocumentationSettings.InlineCodeHighlightingMode =
        if (isNeutral()) DocumentationSettings.InlineCodeHighlightingMode.NO_HIGHLIGHTING
        else DocumentationSettings.InlineCodeHighlightingMode.AS_DEFAULT_CODE

    companion object {
        private val neutralScope: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        private fun isNeutral(): Boolean = neutralScope.get()

        /**
         * Runs [block] with theme-neutral settings on the calling thread. [block] must not suspend:
         * call it inside `readAction { }`, which runs its lambda on the calling thread.
         */
        fun <T> neutral(block: () -> T): T {
            val previous = neutralScope.get()
            neutralScope.set(true)
            try {
                return block()
            }
            finally {
                neutralScope.set(previous)
            }
        }

        /** The coroutine form of [neutral]: the scope holds on every thread the coroutine resumes on. */
        fun neutralContext(): CoroutineContext = neutralScope.asContextElement(true)
    }
}
