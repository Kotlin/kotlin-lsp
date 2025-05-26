// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens.encoding

import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenModifier
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenRegistry
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenWithRange
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range

object SemanticTokensEncoder {
    fun encode(ranges: List<LSSemanticTokenWithRange>, registry: LSSemanticTokenRegistry): List<Int> {
        if (ranges.isEmpty()) return emptyList()
        val rangesSorted = ranges.makeTokensSpanSingleLine().sortedBy { it.range.start }
        var previous = Position.ZERO
        val result = mutableListOf<Int>()
        for (range in rangesSorted) {
            val encoded = encode(range, previous, registry)
            result.addAll(encoded)
            previous = range.range.start
        }
        return result
    }

    /**
     * Not all editors support multiline tokens `SemanticTokensClientCapabilities.makeSingleLineTokens`
     * even vscode at this moment does not: https://github.com/microsoft/vscode/issues/200764
     *
     * So we just make those tokens to be single-line ones by unwrapping them.
     * Probably later, we should take the `SemanticTokensClientCapabilities.makeSingleLineTokens` into account and do no unwrap if this capability is supported.
     */
    private fun List<LSSemanticTokenWithRange>.makeTokensSpanSingleLine(): List<LSSemanticTokenWithRange> = buildList {
        for (range in this@makeTokensSpanSingleLine) {
            if (range.range.start.line == range.range.end.line) {
                add(range)
            } else {
                val lines = range.range.getLineRanges()
                for (lineRange in lines) {
                    add(LSSemanticTokenWithRange(range.token, lineRange))
                }
            }
        }
    }

    private fun Range.getLineRanges(): List<Range> {
        if (start.line == end.line) {
            return listOf(this)
        } else {
            return buildList {
                add(Range.fromPositionTillLineEnd(start))
                for (line in start.line + 1 until end.line) {
                    add(Range.fullLine(line))
                }
                add(Range.fromLineStartTillPosition(end))
            }
        }
    }

    /**
     * From official doc https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
     *  So each token is represented using 5 integers. A specific token i in the file consists of the following array indices:
     *     at index 5*i - deltaLine: token line number, relative to the start of the previous token
     *     at index 5*i+1 - deltaStart: token start character, relative to the start of the previous token (relative to 0 or the previous token’s start if they are on the same line)
     *     at index 5*i+2 - length: the length of the token.
     *     at index 5*i+3 - tokenType: will be looked up in SemanticTokensLegend.tokenTypes. We currently ask that tokenType < 65536.
     *     at index 5*i+4 - tokenModifiers: each set bit will be looked up in SemanticTokensLegend.tokenModifiers
     */
    private fun encode(range: LSSemanticTokenWithRange, from: Position, registry: LSSemanticTokenRegistry): List<Int> {
        return listOf(
            /*0*/ range.range.start.line - from.line,
            /*1*/ encodeDeltaStart(from, range.range),
            /*2*/ encodeLength(range.range),
            /*3*/ registry.typeToIndex(range.token.type),
            /*4*/ encodeModifiers(range.token.modifiers, registry),
        )
    }

    private fun encodeLength(range: Range): Int {
        require(range.start.line == range.end.line)
        return range.end.character - range.start.character
    }

    private fun encodeDeltaStart(from: Position, range: Range): Int = when {
        from.line == range.start.line -> range.start.character - from.character
        else -> range.start.character
    }

    private fun encodeModifiers(modifiers: List<LSSemanticTokenModifier>, registry: LSSemanticTokenRegistry): Int {
        var encoded = 0
        for (modifier in modifiers) {
            val modifierIndex = registry.modifierToIndex(modifier)
            encoded = encoded or (1 shl modifierIndex)
        }
        return encoded
    }
}