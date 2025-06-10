// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.Shutdown
import kotlinx.coroutines.CompletableDeferred

context(LSServer, LSConfiguration)
internal fun LspHandlersBuilder.shutdownRequest(exitSignal: CompletableDeferred<Unit>?) {
    request(Shutdown) {
        // TODO All the requests and notifications after this one should return InvalidRequest
        // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#shutdown
    }
    notification(ExitNotificationType) {
        exitSignal?.complete(Unit)
    }
}
