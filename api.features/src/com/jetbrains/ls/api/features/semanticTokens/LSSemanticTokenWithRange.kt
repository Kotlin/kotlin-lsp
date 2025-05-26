// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.ls.api.features.semanticTokens

import com.jetbrains.lsp.protocol.Range
import java.util.*

class LSSemanticTokenWithRange(
    val token: LSSemanticToken,
    val range: Range,
) {
    override fun toString(): String = "LSRangeWithSemanticToken($token, $range)"

    override fun equals(other: Any?): Boolean =
        this === other ||
                other is LSSemanticTokenWithRange
                && other.range == range
                && other.token == token

    override fun hashCode(): Int = Objects.hash(range, token)
}

class LSSemanticToken(
    val type: LSSemanticTokenType,
    val modifiers: List<LSSemanticTokenModifier> = emptyList(),
) {
    fun withModifiers(vararg modifiers: LSSemanticTokenModifier): LSSemanticToken =
        LSSemanticToken(type, this.modifiers + modifiers)

    override fun toString(): String = "LSSemanticToken($type, $modifiers)"

    override fun equals(other: Any?): Boolean =
        this === other ||
                other is LSSemanticToken
                && other.type == type
                && other.modifiers == modifiers

    override fun hashCode(): Int = Objects.hash(type)
}