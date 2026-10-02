// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.definition

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.core.features.LSDefinitionLocation
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.DefinitionParams
import com.jetbrains.lsp.protocol.LocationLink
import com.jetbrains.lsp.protocol.LocationOrLink
import kotlinx.coroutines.flow.map

object LSDefinition {
    val scope: Scope = Scope("lsp.definition")
    private val tracer = TelemetryManager.getTracer(scope)

    /**
     * Answers `textDocument/definition`.
     *
     * The answer has one kind for the whole session, also across partial results:
     * [LocationLink]s with the origin range for a client with `definition.linkSupport`, plain locations for other clients.
     */
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getDefinition(params: DefinitionParams): List<LocationOrLink> {
        val links = server.config.clientSupportsDefinitionLinks
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = LocationOrLink.serializer(),
            providers = configuration.entriesFor<LSDefinitionProvider>(params.textDocument),
            getResults = { definitionProvider ->
                tracer.traceProvider(
                    spanName = "provider.definition",
                    provider = definitionProvider,
                    resultsFlow = definitionProvider.provideDefinitions(params).map { it.toLocationOrLink(links) },
                )
            },
        )
    }

    private fun LSDefinitionLocation.toLocationOrLink(links: Boolean): LocationOrLink {
        if (!links) return location
        return LocationLink(
            originSelectionRange = origin,
            targetUri = location.uri,
            targetRange = location.range,
            targetSelectionRange = location.range,
        )
    }
}
