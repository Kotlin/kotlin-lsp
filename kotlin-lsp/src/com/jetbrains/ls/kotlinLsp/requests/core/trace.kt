// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.requests.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.lsp.implementation.LspHandlerContext
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.SetTraceNotificationType

context(_: LSServer, _: LSConfiguration)
internal fun LspHandlersBuilder.setTraceNotification() {
    notification(SetTraceNotificationType) { traceParams ->
        Client.update { it.copy(trace = traceParams.value) }
    }
}