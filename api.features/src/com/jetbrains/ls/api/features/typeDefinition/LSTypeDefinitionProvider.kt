// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.typeDefinition

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSLanguageSpecificConfigurationEntry
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.TypeDefinitionParams
import kotlinx.coroutines.flow.Flow

interface LSTypeDefinitionProvider : LSLanguageSpecificConfigurationEntry {
    context(server: LSServer, handlerContext: LspHandlerContext)
    fun provideTypeDefinitions(params: TypeDefinitionParams): Flow<Location>
}
