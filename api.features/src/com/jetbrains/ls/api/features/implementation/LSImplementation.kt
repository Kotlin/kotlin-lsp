// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.implementation

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.ImplementationParams
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.Locations

object LSImplementation {
    val scope: Scope = Scope("lsp.implementation")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getImplementation(params: ImplementationParams): Locations? {
        val locations = LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = Location.serializer(),
            providers = configuration.entriesFor<LSImplementationProvider>(params.textDocument),
            getResults = { implementationProvider ->
                tracer.traceProvider(
                    spanName = "provider.implementation",
                    provider = implementationProvider,
                    resultsFlow = implementationProvider.provideImplementations(params),
                )
            },
        )
        return if (locations.isEmpty()) null else Locations(locations)
    }
}
