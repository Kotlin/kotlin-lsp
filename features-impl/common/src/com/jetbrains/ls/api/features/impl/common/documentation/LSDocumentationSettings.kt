// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.documentation

import com.intellij.lang.documentation.ClientDocumentationSettings
import com.intellij.lang.documentation.DocumentationSettings

/**
 * Stub impl of [ClientDocumentationSettings].
 * 
 * TODO (mbo): handle ide settings ? 
 */
internal class LSDocumentationSettings : ClientDocumentationSettings {
    override fun isHighlightingOfQuickDocSignaturesEnabled(): Boolean = true

    override fun isHighlightingOfCodeBlocksEnabled(): Boolean = true

    override fun isSemanticHighlightingOfLinksEnabled(): Boolean = false

    override fun isCodeBackgroundEnabled(): Boolean = true

    override fun getInlineCodeHighlightingMode(): DocumentationSettings.InlineCodeHighlightingMode = DocumentationSettings.InlineCodeHighlightingMode.AS_DEFAULT_CODE
}
