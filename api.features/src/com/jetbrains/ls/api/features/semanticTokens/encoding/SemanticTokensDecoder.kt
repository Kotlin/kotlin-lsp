// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens.encoding

import com.jetbrains.ls.api.features.semanticTokens.LSSemanticToken
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenModifier
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenRegistry
import com.jetbrains.ls.api.features.semanticTokens.LSSemanticTokenWithRange
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range

object SemanticTokensDecoder {
    fun decode(data: List<Int>, registry: LSSemanticTokenRegistry): List<LSSemanticTokenWithRange> {
        require(data.size % 5 == 0) { "Data must be a multiple of 5 according the LSP protocol spec" }
        if (data.isEmpty()) return emptyList()

        var previous = Position.ZERO
        val result = mutableListOf<LSSemanticTokenWithRange>()
        for (i in 0 until data.size / 5) {
            val decoded = decode(data, i * 5, previous, registry)
            result += decoded
            previous = decoded.range.start
        }
        return result
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
    private fun decode(data: List<Int>, from: Int, previous: Position, registry: LSSemanticTokenRegistry): LSSemanticTokenWithRange {
        val deltaLine = data[from + 0]
        val deltaStart = data[from + 1]

        val lineNumber = previous.line + deltaLine
        val tokenStart = if (deltaLine == 0) previous.character + deltaStart else deltaStart

        return LSSemanticTokenWithRange(
            LSSemanticToken(
                type = registry.indexToType(data[from + 3]),
                modifiers = decodeModifiers(data[from + 4], registry),
            ),
            Range(
                start = Position(lineNumber, tokenStart),
                end = Position(lineNumber, tokenStart + data[from + 2])
            )
        )
    }


    private fun decodeModifiers(modifiers: Int, registry: LSSemanticTokenRegistry): List<LSSemanticTokenModifier> {
        if (modifiers == 0) return emptyList()
        val result = mutableListOf<LSSemanticTokenModifier>()
        var m = modifiers
        var i = 0
        while (m > 0) {
            if (m and 1 == 1) {
                result.add(registry.indexToModifier(i))
            }
            m = m shr 1
            i++
        }
        return result
    }
}