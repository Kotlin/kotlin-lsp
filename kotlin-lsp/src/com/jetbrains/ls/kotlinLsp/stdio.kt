// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.jetbrains.lsp.implementation.ByteWriter
import com.jetbrains.lsp.implementation.LspConnection
import com.jetbrains.lsp.implementation.cancel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import java.io.InputStream
import java.io.OutputStream

suspend fun stdioConnection(
    inputStream: InputStream,
    outputStream: OutputStream,
    body: suspend CoroutineScope.(LspConnection) -> Unit
) {
    coroutineScope {
        body(StdioConnection(inputStream, outputStream))
    }
}

private class StdioConnection(
    inputStream: InputStream,
    outputStream: OutputStream,
) : LspConnection {
    override val input = KtorByteReader(inputStream.toByteReadChannel())
    override val output: ByteWriter = KtorByteWriter(outputStream.asByteWriteChannel())

    override fun close() {
        input.cancel()
        output.cancel(kotlinx.io.IOException("cancelled"))
    }

    override fun isAlive(): Boolean {
        return !input.isClosedForRead || !output.isClosedForWrite
    }
}
