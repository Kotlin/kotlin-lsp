// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.impl.common.definitions

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.lsDefinitions
import com.jetbrains.ls.api.core.project
import com.jetbrains.ls.api.core.util.TargetKind
import com.jetbrains.ls.api.features.definition.LSDefinitionProvider
import com.jetbrains.ls.api.features.language.LSLanguage
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LSCommonDefinitionProvider(
    override val supportedLanguages: Set<LSLanguage>,
    private val targetKinds: Set<TargetKind>,
) : LSDefinitionProvider {
    context(server: LSServer, handlerContext: LspHandlerContext)
    override fun provideDefinitions(params: DefinitionParams): Flow<Location> = flow {
        server.withAnalysisContext {
            lsDefinitions(project, params, targetKinds)
        }.forEach { location -> emit(location) }
    }
}
