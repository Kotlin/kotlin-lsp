// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.typeDefinition

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Locations
import com.jetbrains.lsp.protocol.TypeDefinitionParams

object LSTypeDefinition {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getTypeDefinition(params: TypeDefinitionParams): Locations? {
        val locations = LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = Location.serializer(),
            providers = entriesFor<LSTypeDefinitionProvider>(params.textDocument),
            getResults = { provider -> provider.provideTypeDefinitions(params) },
        )
        return if (locations.isEmpty()) null else Locations(locations)
    }
}
