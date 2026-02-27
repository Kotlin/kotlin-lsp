// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.definition

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProviderResults
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.Location

object LSDefinition {
    val scope: Scope = Scope("lsp.definition")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getDefinition(params: DefinitionParams): List<Location> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = Location.serializer(),
            providers = configuration.entriesFor<LSDefinitionProvider>(params.textDocument),
            getResults = { definitionProvider ->
                tracer.traceProviderResults(
                    spanName = "provider.definition",
                    provider = definitionProvider,
                    results = definitionProvider.provideDefinitions(params),
                )
            },
        )
    }
}
