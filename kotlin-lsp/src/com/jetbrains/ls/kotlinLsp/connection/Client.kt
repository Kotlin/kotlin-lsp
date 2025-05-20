package com.jetbrains.ls.kotlinLsp.connection

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.protocol.TraceValue
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

internal data class Client(
    val lspClient: LspClient,
    val trace: TraceValue? = null,
) {
    companion object {
        val current: Client?
            get() = ClientConnectionHolder.ThreadBound.get()?.connection

        fun contextElement(lspClient: LspClient): ThreadContextElement<*> {
            return ClientConnectionHolder.ThreadBound.asContextElement(
                ClientConnectionHolder(Client(lspClient))
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