// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.ls.api.core.util.retryWithBackOff
import com.jetbrains.lsp.implementation.ByteReader
import com.jetbrains.lsp.implementation.ByteWriter
import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspConnection
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.io.Sink
import kotlinx.io.Source

private val LOG = logger<LspClient>()

suspend fun tcpServer(config: TcpConnectionConfig.Server, server: suspend CoroutineScope.(LspConnection) -> Unit) {
    SelectorManager(Dispatchers.IO).use { selectorManager ->
        aSocket(selectorManager).tcp().bind(config.host, config.port).use { serverSocket ->
            LOG.info("Server is listening on ${serverSocket.localAddress}")

            supervisorScope {
                var hadClient = false
                fun shouldAccept() = !hadClient || config.isMultiClient

                while (shouldAccept()) {
                    val client = serverSocket.accept()
                    val clientAddress = client.remoteAddress
                    hadClient = true
                    LOG.info("A new client connected at ${clientAddress}")
                    launch(
                        context = CoroutineName("client at ${clientAddress}"),
                        start = CoroutineStart.ATOMIC,
                    ) {
                        try {
                            client.use { clientSocket ->
                                coroutineScope { server(KtorSocketConnection(clientSocket)) }
                            }
                        } finally {
                            LOG.info("Client disconnected ${clientAddress}")
                        }
                    }
                }
            }
        }
    }
}


suspend fun tcpClient(
    config: TcpConnectionConfig.Client,
    body: suspend CoroutineScope.(LspConnection) -> Unit,
) {
    SelectorManager(Dispatchers.IO).use { selectorManager ->
        retryWithBackOff(onError = { e, backoff ->
            LOG.warn("Retrying ${config.host}:${config.port} in $backoff... (error: ${e.message})")
        }) {
            aSocket(selectorManager).tcp().connect(config.host, config.port).use { server ->
                LOG.info("Client is connected to server ${server.remoteAddress}")
                try {
                    coroutineScope { body(KtorSocketConnection(server)) }
                } finally {
                    LOG.info("Client disconnected from the server")
                }
            }
        }
    }
}

sealed interface TcpConnectionConfig {
    val host: String
    val port: Int

    val isMultiClient: Boolean

    data class Client(
        override val host: String,
        override val port: Int,
    ) : TcpConnectionConfig {
        override val isMultiClient: Boolean = false
    }

    data class Server(
        override val host: String,
        override val port: Int,
        override val isMultiClient: Boolean,
    ) : TcpConnectionConfig
}

@OptIn(InternalAPI::class)
class KtorByteReader(val input: ByteReadChannel) : ByteReader {
    override val closedCause: Throwable?
        get() = input.closedCause
    override val isClosedForRead: Boolean
        get() = input.isClosedForRead
    override val readBuffer: Source
        get() = input.readBuffer

    override suspend fun awaitContent(min: Int): Boolean = input.awaitContent()
    override fun cancel(cause: Throwable?): Unit = input.cancel(cause)
}

@OptIn(InternalAPI::class)
class KtorByteWriter(val output: ByteWriteChannel) : ByteWriter {
    override val isClosedForWrite: Boolean
        get() = output.isClosedForWrite
    override val closedCause: Throwable?
        get() = output.closedCause
    override val writeBuffer: Sink
        get() = output.writeBuffer

    override suspend fun flush(): Unit = output.flush()
    override suspend fun flushAndClose(): Unit = output.flushAndClose()
    override fun cancel(cause: Throwable?): Unit = output.cancel(cause)
}

class KtorSocketConnection(private val socket: Socket) : LspConnection {
    override val input: ByteReader = KtorByteReader(socket.openReadChannel())
    override val output: ByteWriter = KtorByteWriter(socket.openWriteChannel(autoFlush = true))

    override fun close() {
        socket.close()
    }

    override fun isAlive(): Boolean {
        return !socket.isClosed
    }
}
