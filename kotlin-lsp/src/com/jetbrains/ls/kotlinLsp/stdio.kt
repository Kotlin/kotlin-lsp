// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.kotlinLsp

import com.jetbrains.lsp.implementation.LspConnection
import io.ktor.utils.io.ByteWriteChannel
import com.jetbrains.ls.kotlinLsp.ktorHack.asByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
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
    override val input = inputStream.toByteReadChannel()
    override val output: ByteWriteChannel = outputStream.asByteWriteChannel()

    override fun close() {
        input.cancel()
        output.cancel(kotlinx.io.IOException("cancelled"))
    }

    override fun isAlive(): Boolean {
        return !input.isClosedForRead || !output.isClosedForWrite
    }
}
