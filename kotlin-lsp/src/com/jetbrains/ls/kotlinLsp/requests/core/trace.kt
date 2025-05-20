package com.jetbrains.ls.kotlinLsp.requests.core

import com.jetbrains.ls.api.core.LSServer
import com.jetbrains.ls.api.features.LSConfiguration
import com.jetbrains.ls.kotlinLsp.connection.Client
import com.jetbrains.lsp.implementation.LspHandlersBuilder
import com.jetbrains.lsp.protocol.SetTraceNotificationType

context(LSServer, LSConfiguration)
internal fun LspHandlersBuilder.setTraceNotification() {
    notification(SetTraceNotificationType) { traceParams ->
        Client.update { it.copy(trace = traceParams) }
    }
}