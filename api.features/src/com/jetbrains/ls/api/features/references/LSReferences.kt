// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.references

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.ReferenceParams
import kotlinx.coroutines.flow.map

object LSReferences {
    /**
     * Answers `textDocument/references`.
     *
     * A client with `initializationOptions.referenceKinds` gets the access and the import flag of each reference,
     * also across partial results. For other clients the references are not classified, and each one is a plain location.
     */
    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getReferences(params: ReferenceParams): List<ReferenceLocation> {
        val classify = server.config.clientSupportsReferenceKinds
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = ReferenceLocation.serializer(),
            providers = configuration.entriesFor<LSReferencesProvider>(params.textDocument),
            getResults = { referencesProvider -> referencesProvider.getReferences(params, classify).map { it.toReferenceLocation() } },
        )
    }
}
