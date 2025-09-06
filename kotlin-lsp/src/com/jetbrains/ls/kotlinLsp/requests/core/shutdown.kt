// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.Shutdown
import kotlinx.coroutines.CompletableDeferred

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.shutdownRequest(exitSignal: CompletableDeferred<Unit>?) {
    request(Shutdown) {
        // TODO All the requests and notifications after this one should return InvalidRequest but only in the single client mode
        // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#shutdown
        null
    }
    notification(ExitNotificationType) {
        exitSignal?.complete(Unit)
    }
}
