// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.implementation

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsImplementations
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.features.implementation.LSImplementationProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.ImplementationParams
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonImplementationProvider(
    override val supportedLanguages: Set<LSLanguage>
) : LSImplementationProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideImplementations(params: ImplementationParams): Flow<Location> = flow {
        server.withAnalysisContext {
            lsImplementations(project, params)
        }.forEach { location -> emit(location) }
    }
}
