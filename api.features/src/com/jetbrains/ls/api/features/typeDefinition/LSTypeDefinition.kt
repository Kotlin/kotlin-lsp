// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.typeDefinition

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Locations
import com.jetbrains.lsp.protocol.TypeDefinitionParams

object LSTypeDefinition {
    val scope: Scope = Scope("lsp.typeDefinition")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getTypeDefinition(params: TypeDefinitionParams): Locations? {
        val locations = LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = Location.serializer(),
            providers = configuration.entriesFor<LSTypeDefinitionProvider>(params.textDocument),
            getResults = { typeDefinitionProvider ->
                tracer.traceProvider(
                    spanName = "provider.typeDefinition",
                    provider = typeDefinitionProvider,
                    resultsFlow = typeDefinitionProvider.provideTypeDefinitions(params),
                )
            },
        )
        return if (locations.isEmpty()) null else Locations(locations)
    }
}
