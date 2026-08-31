// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.references

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsReferences
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.TargetKind
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.ls.api.features.references.LSReferencesProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.ReferenceParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class LSCommonReferencesProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val targetKinds: Set<TargetKind>
) : LSReferencesProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun getReferences(params: ReferenceParams): Flow<Location> = channelFlow {
        server.withAnalysisContext {
            lsReferences(project, params, targetKinds, channel::send)
        }
    }
}
