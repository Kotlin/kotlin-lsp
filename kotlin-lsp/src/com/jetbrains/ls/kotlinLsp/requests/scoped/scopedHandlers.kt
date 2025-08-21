// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.scoped

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.kotlinLsp.requests.core.setTraceNotification
import com.jetbrains.ls.kotlinLsp.requests.core.shutdownRequest
import com.jetbrains.ls.kotlinLsp.requests.scoped.core.scopedFileUpdateRequests
import com.jetbrains.ls.kotlinLsp.requests.scoped.core.scopedInitializeRequest
import com.jetbrains.ls.snapshot.api.impl.core.scoped.ClientScopedSession
import com.jetbrains.ls.snapshot.api.impl.core.toFileUrl
import com.jetbrains.lsp.implementation.LspHandlers
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.protocol.DocumentUri
import kotlinx.coroutines.CompletableDeferred

context(_: LSServer)
fun createScopedLSPHandlers(config: LSConfiguration, exitSignal: CompletableDeferred<Unit>?): LspHandlers {
    with(config) {
        return lspHandlers {
            scopedInitializeRequest()
            setTraceNotification()
            scopedFileUpdateRequests()
            scopedFeatures()
            shutdownRequest(exitSignal)
        }
    }
}

internal fun ensureRequestUriIsScoped(uri: DocumentUri) {
    require(ClientScopedSession.current?.url == uri.uri.toFileUrl()) {
        "Completion for requested document ${uri.uri} does not match current" +
                "client's session URL! (expected ${ClientScopedSession.current?.url})"
    }
}
