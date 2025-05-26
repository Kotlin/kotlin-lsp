// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.jetbrains.ls.api.features.semanticTokens.encoding.SemanticTokensDecoder
import com.jetbrains.ls.api.features.semanticTokens.encoding.SemanticTokensEncoder
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Test

class SemanticTokensEncoderTest {

    @Test
    fun encodeSameLine() {
        doTestSingleLine(
            expected = listOf(
                //@formatter:off
                0, 0, 2, 0, 0,
                0, 2, 1, 2, 0,
                //@formatter:on
            ),
            registry = LSSemanticTokenRegistry(types("a", "b", "c"), modifiers()),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 2), "a"),
                tokenWithRange(pos(0, 2), pos(0, 3), "c"),
            )
        )
    }

    @Test
    fun encodeModifier_0() {
        doTestSingleLine(
            expected = listOf(
                0, 0, 10, 0, bin("1"),
            ),
            registry = LSSemanticTokenRegistry(types("a"), modifiers("x", "y", "z")),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a", "x")
            )
        )
    }

    @Test
    fun encodeModifier_10() {
        doTestSingleLine(
            expected = listOf(
                0, 0, 10, 0, bin("10"),
            ),
            registry = LSSemanticTokenRegistry(types("a"), modifiers("x", "y", "z")),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a", "y"),
            )
        )
    }

    @Test
    fun encodeModifier_101() {
        doTestSingleLine(
            expected = listOf(
                0, 0, 10, 0, bin("101"),
            ),
            registry = LSSemanticTokenRegistry(types("a"), modifiers("x", "y", "z")),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a", "x", "z"),
            )
        )
    }

    @Test
    fun encodeModifier_111() {
        doTestSingleLine(
            expected = listOf(
                0, 0, 10, 0, bin("111"),
            ),
            registry = LSSemanticTokenRegistry(types("a"), modifiers("x", "y", "z")),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a", "x", "y", "z"),
            )
        )
    }


    @Test
    fun encodeMultiline() {
        doTestMultiLine(
            expected = listOf(
                // @formatter:off
                0, 0, EOL, 0, 0,
                1, 0, 10, 0, 0,
                // @formatter:on
            ),
            expectedDecoded = listOf(
                tokenWithRange(pos(0, 0), Position.lineEnd(0), "a"),
                tokenWithRange(pos(1, 0), pos(1, 10), "a"),
            ),
            registry = LSSemanticTokenRegistry(types("a"), modifiers()),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(1, 10), "a")
            )
        )
    }

    @Test
    fun encodeSingleAndMultilineMixed() {
        doTestMultiLine(
            expected = listOf(
                // @formatter:off
                0, 0, 10, 0, 0,
                0, 10, EOL - 10, 1, 0,
                1, 0, 25, 1, 0,
                0, 27, 6, 2, 0,
                // @formatter:on
            ),
            expectedDecoded = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a"),
                tokenWithRange(pos(0, 10), Position.lineEnd(0), "b"),
                tokenWithRange(pos(1, 0), pos(1, 25), "b"),
                tokenWithRange(pos(1, 27), pos(1, 33), "c"),
            ),
            registry = LSSemanticTokenRegistry(types = types("a", "b", "c"), modifiers = modifiers()),
            tokens = listOf(
                tokenWithRange(pos(0, 0), pos(0, 10), "a"),
                tokenWithRange(pos(0, 10), pos(1, 25), "b"),
                tokenWithRange(pos(1, 27), pos(1, 33), "c")
            )
        )
    }


    private fun doTestSingleLine(
        expected: List<Int>,
        registry: LSSemanticTokenRegistry,
        tokens: List<LSSemanticTokenWithRange>,
    ) {
        val encoded = SemanticTokensEncoder.encode(tokens.toList(), registry)
        assertEquals(0, encoded.size % 5)
        assertEquals(tokens.size, encoded.size / 5)
        for (i in 0 until expected.size / 5) {
            val initialToken = tokens[i]

            val tokenType = encoded[i * 5 + 3]
            assertEquals(registry.typeToIndex(initialToken.token.type), tokenType)

            val modifiers = encoded[i * 5 + 4]
            if (initialToken.token.modifiers.isEmpty()) {
                assertEquals(0, modifiers)
            }
        }
        assertEquals(expected, encoded)
        checkDecoding(encoded, registry, tokens)
    }

    private fun doTestMultiLine(
        expected: List<Int>,
        expectedDecoded: List<LSSemanticTokenWithRange>,
        registry: LSSemanticTokenRegistry,
        tokens: List<LSSemanticTokenWithRange>,
    ) {
        val encoded = SemanticTokensEncoder.encode(tokens.toList(), registry)
        assertEquals(0, encoded.size % 5)
        assertIterableEquals(expected, encoded)

        checkDecoding(encoded, registry, expectedDecoded)
    }

    private fun checkDecoding(
        encoded: List<Int>,
        registry: LSSemanticTokenRegistry,
        expectedDecoded: List<LSSemanticTokenWithRange>
    ) {
        val decoded = SemanticTokensDecoder.decode(encoded, registry)
        assertIterableEquals(expectedDecoded, decoded)
    }

    private fun tokenWithRange(start: Position, end: Position, type: String, vararg modifiers: String) = LSSemanticTokenWithRange(
        range = Range(start, end),
        token = LSSemanticToken(
            type = type(type),
            modifiers = modifiers(*modifiers),
        )
    )

    private fun pos(line: Int, character: Int): Position = Position(line, character)

    private fun modifiers(vararg names: String) = names.map { modifier(it) }
    private fun modifier(string: String): LSSemanticTokenModifierCustom = LSSemanticTokenModifierCustom(string)

    private fun type(name: String): LSSemanticTokenTypeCustom = LSSemanticTokenTypeCustom(name)
    private fun types(vararg names: String) = names.map { type(it) }

    private fun bin(b: String): Int = b.toInt(2)

    private val EOL = Position.EOL_INDEX
}