/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("INVISIBLE_REFERENCE")
/*
Copied from https://github.com/ktorio/ktor/pull/4594 until ktor is not updated in monorepo
 */

package com.jetbrains.ls.kotlinLsp.ktorHack

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.CLOSED
import io.ktor.utils.io.CloseToken
import io.ktor.utils.io.InternalAPI
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.OutputStream

/**
 * Creates a [io.ktor.utils.io.ByteWriteChannel] that writes to this [Sink].
 *
 * Example usage:
 * ```kotlin
 * suspend fun writeMessage(raw: RawSink) {
 *     val channel = raw.asByteWriteChannel()
 *     channel.writeByte(42)
 *     channel.flushAndClose()
 * }
 *
 * val buffer = Buffer()
 * writeMessage(buffer)
 * buffer.readByte() // 42
 * ```
 *
 * Please note that the channel will be buffered even if the sink is not.
 */
public fun RawSink.asByteWriteChannel(): ByteWriteChannel = SinkByteWriteChannel(this)

@Suppress("INVISIBLE_REFERENCE")
internal class SinkByteWriteChannel(origin: RawSink) : ByteWriteChannel {
    @Volatile
    var closed: CloseToken? = null
    private val buffer = origin.buffered()

    override val isClosedForWrite: Boolean
        get() = closed != null

    override val closedCause: Throwable?
        get() = closed?.cause

    @InternalAPI
    override val writeBuffer: Sink
        get() {
            if (isClosedForWrite) throw closedCause ?: IOException("Channel is closed for write")
            return buffer
        }

    @OptIn(InternalAPI::class)
    override suspend fun flush() {
        writeBuffer.flush()
    }

    @OptIn(InternalAPI::class)
    override suspend fun flushAndClose() {
        writeBuffer.flush()
        closed = CLOSED
    }

    @OptIn(InternalAPI::class)
    override fun cancel(cause: Throwable?) {
        val token = if (cause == null) CLOSED else CloseToken(cause)
        closed = token
    }
}

/**
 * Converts this [OutputStream] into a [ByteWriteChannel], enabling asynchronous writing of byte sequences.
 *
 * ```kotlin
 * val outputStream: OutputStream = FileOutputStream("file.txt")
 * val channel: ByteWriteChannel = outputStream.asByteWriteChannel()
 * channel.writeFully("Hello, World!".toByteArray())
 * channel.flushAndClose() // Ensure the data is written to the OutputStream
 * ```
 *
 * All operations on the [ByteWriteChannel] are buffered: the underlying [OutputStream] will be receiving bytes
 * when the [ByteWriteChannel.flush] happens.
 */
public fun OutputStream.asByteWriteChannel(): ByteWriteChannel = asSink().asByteWriteChannel()