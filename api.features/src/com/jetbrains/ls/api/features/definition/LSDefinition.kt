// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.definition

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.Location

object LSDefinition {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getDefinition(params: DefinitionParams): List<Location> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = Location.serializer(),
            providers = entriesFor<LSDefinitionProvider>(params.textDocument),
            getResults = { definitionProvider -> definitionProvider.provideDefinitions(params) },
        )
    }
}
