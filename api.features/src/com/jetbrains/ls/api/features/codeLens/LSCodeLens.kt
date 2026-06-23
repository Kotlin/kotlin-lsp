// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.codeLens

import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.api.features.partialResults.LSConcurrentResponseHandler
import com.jetbrains.ls.api.features.utils.traceProvider
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.protocol.CodeLens
import com.jetbrains.lsp.protocol.CodeLensParams

object LSCodeLens {
    val scope: Scope = Scope("lsp.codeLens")
    private val tracer = TelemetryManager.getTracer(scope)

    context(server: LSServer, configuration: LSConfiguration, handlerContext: LspHandlerContext)
    suspend fun getCodeLenses(params: CodeLensParams): List<CodeLens> {
        return LSConcurrentResponseHandler.streamResultsIfPossibleOrRespondDirectly(
            partialResultToken = params.partialResultToken,
            resultSerializer = CodeLens.serializer(),
            providers = configuration.entriesFor<LSCodeLensProvider>(params.textDocument),
            getResults = { provider ->
                tracer.traceProvider(
                    spanName = "provider.codeLens",
                    provider = provider,
                    resultsFlow = provider.getCodeLenses(params),
                )
            },
        )
    }
}
