package com.jetbrains.ls.api.features.hover

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams

object LSHover {
    context(LSConfiguration, LSServer)
    suspend fun getHover(params: HoverParams): Hover? {
        return entriesFor<LSHoverProvider>(params.textDocument).firstNotNullOfOrNull { it.getHover(params) }
    }
}