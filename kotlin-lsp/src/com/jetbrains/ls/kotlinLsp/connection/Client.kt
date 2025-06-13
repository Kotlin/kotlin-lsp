// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp.connection

import com.jetbrains.ls.kotlinLsp.KotlinLspServerRunConfig
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.TraceValue
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

data class Client(
    val lspClient: LspClient,
    val runConfig: KotlinLspServerRunConfig?,
    val trace: TraceValue?,
) {
    companion object {
        val current: Client?
            get() = ClientConnectionHolder.ThreadBound.get()?.connection

        fun contextElement(lspClient: LspClient, runConfig: KotlinLspServerRunConfig?): ThreadContextElement<*> {
            return ClientConnectionHolder.ThreadBound.asContextElement(
                ClientConnectionHolder(Client(lspClient, runConfig, trace = null))
            )
        }

        fun update(update: (Client) -> Client) {
            val holder = ClientConnectionHolder.ThreadBound.get() ?: error("Not inside connection scope")
            holder.connection = update(holder.connection)
        }
    }
}

private class ClientConnectionHolder(
    @Volatile var connection: Client,
) {
    companion object {
        val ThreadBound: ThreadLocal<ClientConnectionHolder> = ThreadLocal()
    }
}