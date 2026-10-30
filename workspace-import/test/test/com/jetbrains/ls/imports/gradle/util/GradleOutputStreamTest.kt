// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.imports.gradle.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [GradleOutputStream] hands out lines without their terminator. */
class GradleOutputStreamTest {
    private fun lines(write: (GradleOutputStream) -> Unit): List<String> {
        val lines = mutableListOf<String>()
        GradleOutputStream { lines += it }.use(write)
        return lines
    }

    @Test
    fun `LF and CRLF lines come out bare`() {
        assertEquals(listOf("a", "b", "c"), lines { it.write("a\nb\r\nc\n".toByteArray()) })
    }

    @Test
    fun `an empty line stays an empty string`() {
        assertEquals(listOf("a", "", "", "b"), lines { it.write("a\n\n\r\nb\n".toByteArray()) })
    }

    @Test
    fun `a lone CR inside a line is kept`() {
        assertEquals(listOf("50%\r100%"), lines { it.write("50%\r100%\n".toByteArray()) })
    }

    @Test
    fun `a line split across writes is joined`() {
        assertEquals(listOf("hello world", "next"), lines {
            it.write("hel".toByteArray())
            it.flush()
            it.write("lo wo".toByteArray())
            it.write("rld\r".toByteArray())
            it.write("\nnext\n".toByteArray())
        })
    }

    @Test
    fun `a multi-byte char split across two writes is decoded`() {
        val bytes = "ü → x\n".toByteArray()
        assertEquals(listOf("ü → x"), lines {
            it.write(bytes, 0, 1)
            it.write(bytes, 1, bytes.size - 1)
        })
        assertEquals(listOf("ü → x"), lines {
            it.write(bytes, 0, 4)
            it.write(bytes, 4, bytes.size - 4)
        })
    }

    @Test
    fun `multi-byte chars written byte by byte are decoded`() {
        assertEquals(listOf("ü → x", ""), lines { out -> "ü → x\r\n\n".toByteArray().forEach { out.write(it.toInt()) } })
    }

    @Test
    fun `close emits the partial last line`() {
        assertEquals(listOf("a", "tail"), lines { it.write("a\ntail".toByteArray()) })
    }

    @Test
    fun `close emits nothing for an empty buffer`() {
        assertEquals(emptyList<String>(), lines {})
        assertEquals(listOf("a"), lines { it.write("a\n".toByteArray()) })
    }
}
