package com.jetbrains.ls.api.features.hover

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams

interface LSHoverProvider : LSLanguageSpecificConfigurationEntry {
    context(LSServer)
    suspend fun getHover(params: HoverParams): Hover?
}

