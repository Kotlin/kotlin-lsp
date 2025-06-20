// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.hover

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.lsp.protocol.Hover
import com.jetbrains.lsp.protocol.HoverParams

object LSHover {
    context(_: LSConfiguration, _: LSServer)
    suspend fun getHover(params: HoverParams): Hover? {
        return entriesFor<LSHoverProvider>(params.textDocument).firstNotNullOfOrNull { it.getHover(params) }
    }
}