// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.util

import java.io.OutputStream
import java.nio.charset.StandardCharsets

class GradleOutputStream(private val lineConsumer: (line: String) -> Unit) : OutputStream() {

    private companion object {
        private const val NEWLINE_CHAR: Char = '\n'
    }

    private val buffer = StringBuilder()

    override fun write(b: Int) {
        val c = b.toChar()
        buffer.append(c)
        if (c == NEWLINE_CHAR) {
            doFlush()
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var start = off
        val maxOffset = off + len
        for (i in off..<maxOffset) {
            if (b[i] == NEWLINE_CHAR.code.toByte()) {
                buffer.append(String(b, start, i - start + 1, StandardCharsets.UTF_8))
                doFlush()
                start = i + 1
            }
        }

        if (start < maxOffset) {
            buffer.append(String(b, start, maxOffset - start, StandardCharsets.UTF_8))
        }
    }

    fun doFlush() {
        lineConsumer(buffer.toString())
        buffer.setLength(0)
    }
}
