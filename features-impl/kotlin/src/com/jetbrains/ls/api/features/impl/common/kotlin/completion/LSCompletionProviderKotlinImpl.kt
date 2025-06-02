// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.kotlin.completion

import com.jetbrains.ls.api.features.impl.common.completion.LSAbstractCompletionProvider
import com.jetbrains.ls.api.features.impl.common.kotlin.language.LSKotlinLanguage
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.protocol.TextEdit
import org.jetbrains.kotlin.resolve.ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE_WITH_DOT

object LSCompletionProviderKotlinImpl : LSAbstractCompletionProvider() {
    override val uniqueId: String = "KotlinCompletionProvider"
    override val supportedLanguages: Set<LSLanguage> = setOf(LSKotlinLanguage)

    override fun transformTextEdits(textEdits: List<TextEdit>): List<TextEdit> =
        textEdits.map {
            it.copy(newText = it.newText.replace(ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE_WITH_DOT, ""))
        }
}