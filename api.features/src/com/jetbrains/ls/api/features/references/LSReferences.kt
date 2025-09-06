// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.references

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.entriesFor
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.ReferenceParams

object LSReferences {
    context(_: LSServer, _: LSConfiguration, _: LspHandlerContext)
    suspend fun getReferences(params: ReferenceParams): List<Location> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            params.partialResultToken,
            Location.serializer(),
            entriesFor<LSReferencesProvider>(params.textDocument),
        ) {
            it.getReferences(params)
        }
    }
}