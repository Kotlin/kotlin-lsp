// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.util

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Splits UTF-8 output into lines and hands each one to [lineConsumer] without its terminator (`\n` or `\r\n`).
 * [flush] keeps a partial line; [close] emits it.
 */
class GradleOutputStream(private val lineConsumer: (line: String) -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
        if (b.toByte() == NEWLINE) emitLine() else buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var start = off
        for (i in off..<off + len) {
            if (b[i] == NEWLINE) {
                buffer.write(b, start, i - start)
                emitLine()
                start = i + 1
            }
        }
        buffer.write(b, start, off + len - start)
    }

    override fun flush() {}

    override fun close() {
        if (buffer.size() > 0) emitLine()
    }

    private fun emitLine() {
        val line = buffer.toString(StandardCharsets.UTF_8).removeSuffix("\r")
        buffer.reset()
        lineConsumer(line)
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
