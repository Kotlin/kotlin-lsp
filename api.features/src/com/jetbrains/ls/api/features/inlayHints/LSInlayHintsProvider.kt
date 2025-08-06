// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.inlayHints

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.ls.api.features.configuration.LSUniqueConfigurationEntry
import com.jetbrains.lsp.protocol.InlayHint
import com.jetbrains.lsp.protocol.InlayHintParams
import kotlinx.coroutines.flow.Flow

interface LSInlayHintsProvider : LSLanguageSpecificConfigurationEntry, LSUniqueConfigurationEntry {
    context(_: LSServer)
    fun getInlayHints(params: InlayHintParams): Flow<InlayHint>

    context(_: LSServer)
    suspend fun resolveInlayHint(hint: InlayHint): InlayHint?
}
