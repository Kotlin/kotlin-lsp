// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.core.util

import com.intellij.mock.MockMultiLineImmutableDocument
import com.intellij.openapi.editor.Document
import com.jetbrains.lsp.protocol.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PositionsTest {
    @Test
    fun `should convert offsets on empty document`() {
        val document = createDocument()

        assertEquals(0, document.offsetByPosition(Position(0, 0)))
        assertEquals(0, document.offsetByPosition(Position(10, 20)))
    }

    @Test
    fun `should convert offsets outside the range of the document`() {
        val document = createDocument(5, 6)

        assertEquals(document.textLength, document.offsetByPosition(Position(1, 6)))
        assertEquals(document.textLength, document.offsetByPosition(Position(1, 7)))
        assertEquals(document.textLength, document.offsetByPosition(Position(2, 0)))
        assertEquals(document.textLength, document.offsetByPosition(Position(2, 10)))
    }


    @Test
    fun `should convert offsets  outside line ranges`() {
        val document = createDocument(5, 6)

        assertEquals(5, document.offsetByPosition(Position(0, 6)))
        assertEquals(5, document.offsetByPosition(Position(0, 7)))
        assertEquals(5, document.offsetByPosition(Position(0, 100)))
        assertEquals(12, document.offsetByPosition(Position(1, 7)))
        assertEquals(12, document.offsetByPosition(Position(1, 8)))
        assertEquals(12, document.offsetByPosition(Position(1, 100)))
    }


    @Test
    fun `should convert offsets inside the range of the document`() {
        val document = createDocument(5, 6)

        testInsideOffset(0, Position(0, 0), document)
        testInsideOffset(1, Position(0, 1), document)
        testInsideOffset(4, Position(0, 4), document)
        testInsideOffset(6, Position(1, 0), document)
        testInsideOffset(10, Position(1, 4), document)
        testInsideOffset(11, Position(1, 5), document)
    }

    private fun testInsideOffset(
        expectedOffset: Int,
        position: Position,
        document: Document,
    ) {
        val actualOffset = document.offsetByPosition(position)
        assertEquals(expectedOffset, actualOffset)

        val positionByOffset = document.positionByOffset(actualOffset)
        assertEquals(position, positionByOffset)
    }


    private fun createDocument(
        vararg lineLengths: Int
    ): Document {
        return createDocument(lineLengths.toList())
    }

    private fun createDocument(
        lineLengths: List<Int>
    ): Document {
        val char = 'a'
        val text = buildString {
            for (length in lineLengths) {
                repeat(length) {
                    append(char)
                }
                if (lineLengths.last() != length) {
                    append('\n')
                }
            }
        }
        return MockMultiLineImmutableDocument(text)
    }
}